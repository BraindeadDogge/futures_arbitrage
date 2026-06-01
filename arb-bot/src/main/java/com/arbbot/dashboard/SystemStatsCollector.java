package com.arbbot.dashboard;

import com.arbbot.dashboard.DashboardSnapshot.SystemStatsDto;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SystemStatsCollector {

    private static final Logger log = LoggerFactory.getLogger(SystemStatsCollector.class);

    private volatile SystemStatsDto latest = new SystemStatsDto(
        -1, -1, 0, 0, 0, 0, 0, 0, 0, "OK");

    private ScheduledExecutorService scheduler;

    // OSHI state — only accessed from the single poll thread
    private SystemInfo si;
    private HardwareAbstractionLayer hal;
    private CentralProcessor processor;
    private GlobalMemory memory;
    private long[] prevCpuTicks;
    private List<NetworkIF> netIfs;
    private long[] prevNetRecv;
    private long[] prevNetSent;
    private long sessionRecvBytes = 0;
    private double maxSeenDownMbps = 0;

    private final MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
    // JMX OS bean: getProcessCpuLoad() returns fraction of *all* CPUs, matching profiler tools
    private final OperatingSystemMXBean osMXBean =
        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("sys-stats").factory());
        scheduler.scheduleAtFixedRate(this::poll, 0, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try { scheduler.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public SystemStatsDto getLatestStats() {
        return latest;
    }

    /** Package-private for testing. */
    static String computeNetStatus(double currentMbps, double maxMbps) {
        if (maxMbps <= 0) return "OK";
        double ratio = currentMbps / maxMbps;
        if (ratio >= 0.95) return "SAT";
        if (ratio >= 0.80) return "HIGH";
        return "OK";
    }

    private void poll() {
        try {
            ensureInit();
            double cpuSys = pollCpuSys();
            double cpuBot = pollCpuBot();
            double[] ram = pollRam();
            double ramBotHeapMb = memBean.getHeapMemoryUsage().getUsed() / 1_048_576.0;
            double[] net = pollNet();
            String netStatus = computeNetStatus(net[0], maxSeenDownMbps);
            latest = new SystemStatsDto(
                cpuSys, cpuBot,
                ram[0], ram[1],
                ramBotHeapMb,
                net[0], net[1], net[2], maxSeenDownMbps,
                netStatus);
        } catch (Exception e) {
            log.debug("System stats poll error: {}", e.getMessage());
        }
    }

    private void ensureInit() {
        if (si != null) return;
        si = new SystemInfo();
        hal = si.getHardware();
        processor = hal.getProcessor();
        memory = hal.getMemory();
        prevCpuTicks = processor.getSystemCpuLoadTicks();
        netIfs = hal.getNetworkIFs().stream()
            .filter(n -> !n.getName().startsWith("lo"))
            .toList();
        prevNetRecv = new long[netIfs.size()];
        prevNetSent = new long[netIfs.size()];
        for (int i = 0; i < netIfs.size(); i++) {
            netIfs.get(i).updateAttributes();
            prevNetRecv[i] = netIfs.get(i).getBytesRecv();
            prevNetSent[i] = netIfs.get(i).getBytesSent();
        }
    }

    private double pollCpuSys() {
        // Correct OSHI pattern: compute delta first (internally fetches current ticks),
        // then save the freshly fetched ticks for the next interval.
        double load = processor.getSystemCpuLoadBetweenTicks(prevCpuTicks) * 100.0;
        prevCpuTicks = processor.getSystemCpuLoadTicks();
        return Double.isFinite(load) ? Math.max(0, Math.min(100, load)) : -1;
    }

    private double pollCpuBot() {
        // JMX getProcessCpuLoad() returns fraction of ALL logical CPUs (0.0–1.0),
        // matching the scale shown by IntelliJ profiler and Activity Monitor.
        // OSHI's getProcessCpuLoadBetweenTicks() returns per-core-equivalent (not
        // normalized), causing "bot > total" when the machine has multiple CPUs.
        double load = osMXBean.getProcessCpuLoad() * 100.0;
        return Double.isFinite(load) && load >= 0 ? load : -1;
    }

    private double[] pollRam() {
        long avail = memory.getAvailable();
        long total = memory.getTotal();
        double usedGb = (total - avail) / 1_073_741_824.0;
        double totalGb = total / 1_073_741_824.0;
        return new double[]{usedGb, totalGb};
    }

    /** Returns [downMbps, upMbps, sessionMb]. */
    private double[] pollNet() {
        long totalRecvDelta = 0, totalSentDelta = 0;
        for (int i = 0; i < netIfs.size(); i++) {
            netIfs.get(i).updateAttributes();
            long recv = netIfs.get(i).getBytesRecv();
            long sent = netIfs.get(i).getBytesSent();
            long recvDelta = Math.max(0, recv - prevNetRecv[i]);
            long sentDelta = Math.max(0, sent - prevNetSent[i]);
            totalRecvDelta += recvDelta;
            totalSentDelta += sentDelta;
            prevNetRecv[i] = recv;
            prevNetSent[i] = sent;
        }
        sessionRecvBytes += totalRecvDelta;
        double downMbps = totalRecvDelta * 8.0 / 1_000_000.0;
        double upMbps   = totalSentDelta * 8.0 / 1_000_000.0;
        double sessionMb = sessionRecvBytes / 1_048_576.0;
        if (downMbps > maxSeenDownMbps) maxSeenDownMbps = downMbps;
        return new double[]{downMbps, upMbps, sessionMb};
    }
}
