package com.arbbot.dashboard;

import static org.junit.jupiter.api.Assertions.*;
import com.arbbot.dashboard.DashboardSnapshot.SystemStatsDto;
import org.junit.jupiter.api.Test;

class SystemStatsCollectorTest {

    @Test
    void getLatestStatsNeverNullBeforeFirstPoll() {
        var collector = new SystemStatsCollector();
        // Before start(), should return a zero-stub, not null
        assertNotNull(collector.getLatestStats());
    }

    @Test
    void startAndStopLifecycle() throws Exception {
        var collector = new SystemStatsCollector();
        collector.start();
        Thread.sleep(1200); // let one poll complete
        SystemStatsDto stats = collector.getLatestStats();
        assertNotNull(stats);
        // CPU sys should be a valid 0–100 value or -1
        assertTrue(stats.cpuSysPct() >= -1 && stats.cpuSysPct() <= 100,
            "cpuSysPct out of range: " + stats.cpuSysPct());
        // RAM should be positive if readable
        assertTrue(stats.ramSysTotalGb() >= 0);
        collector.stop();
    }

    @Test
    void netStatusOkWhenBelowEightyPercent() {
        assertEquals("OK", SystemStatsCollector.computeNetStatus(4.0, 10.0));
    }

    @Test
    void netStatusHighWhenBetweenEightyAndNinetyFive() {
        assertEquals("HIGH", SystemStatsCollector.computeNetStatus(8.5, 10.0));
    }

    @Test
    void netStatusSatWhenAboveNinetyFive() {
        assertEquals("SAT", SystemStatsCollector.computeNetStatus(9.6, 10.0));
    }

    @Test
    void netStatusOkWhenMaxSeenIsZero() {
        // No data yet — should not divide by zero
        assertEquals("OK", SystemStatsCollector.computeNetStatus(0.0, 0.0));
    }
}
