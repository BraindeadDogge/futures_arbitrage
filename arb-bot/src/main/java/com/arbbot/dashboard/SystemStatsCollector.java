package com.arbbot.dashboard;

import com.arbbot.dashboard.DashboardSnapshot.SystemStatsDto;
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
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SystemStatsCollector {

    private static final Logger log = LoggerFactory.getLogger(SystemStatsCollector.class);

    private volatile SystemStatsDto latest = new SystemStatsDto(
        -1, -1, -1, 0, 0, 0, 0, 0, 0, 0, "OK");

    private ScheduledExecutorService scheduler;

    // OSHI state — only accessed from the single poll thread
    private SystemInfo si;
    private HardwareAbstractionLayer hal;
    private CentralProcessor processor;
    private GlobalMemory memory;
    private OperatingSystem os;
    private int pid;
    private long[] prevCpuTicks;
    private OSProcess prevProc;
    private List<NetworkIF> netIfs;
    private long[] prevNetRecv;
    private long[] prevNetSent;
    private long sessionRecvBytes = 0;
    private double maxSeenDownMbps = 0;

    private final MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();

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
            double gpuPct = pollGpu();
            double[] ram = pollRam();
            double ramBotHeapMb = memBean.getHeapMemoryUsage().getUsed() / 1_048_576.0;
            double[] net = pollNet();
            String netStatus = computeNetStatus(net[0], maxSeenDownMbps);
            latest = new SystemStatsDto(
                cpuSys, cpuBot, gpuPct,
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
        os = si.getOperatingSystem();
        pid = (int) ProcessHandle.current().pid();
        prevCpuTicks = processor.getSystemCpuLoadTicks();
        prevProc = os.getProcess(pid);
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
        long[] ticks = processor.getSystemCpuLoadTicks();
        double load = processor.getSystemCpuLoadBetweenTicks(prevCpuTicks) * 100.0;
        prevCpuTicks = ticks;
        return Double.isFinite(load) ? Math.max(0, Math.min(100, load)) : -1;
    }

    private double pollCpuBot() {
        try {
            OSProcess proc = os.getProcess(pid);
            double load = proc.getProcessCpuLoadBetweenTicks(prevProc) * 100.0;
            prevProc = proc;
            return Double.isFinite(load) ? Math.max(0, Math.min(100, load)) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private double pollGpu() {
        // OSHI 6.6.1 GraphicsCard does not expose GPU utilisation; return sentinel
        return -1;
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
