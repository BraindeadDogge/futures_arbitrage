package com.arbbot.integration;

import com.arbbot.exchange.htx.HtxWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class HtxIntegrationTest {

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void allBooksInitializeAndReceiveUpdates() throws InterruptedException {
        // HTX uses "BTC-USDT" format (with hyphen)
        List<String> symbols = List.of("BTC-USDT", "ETH-USDT", "SOL-USDT");

        var obm = new OrderBookManager(5000);
        var health = new HealthMonitor(5000);
        var client = new HtxWsClient(
            "wss://api.hbdm.com/linear-swap-ws",
            symbols, obm, health, new OkHttpClient());

        assertEquals(1, client.shardCount());

        client.connect();
        try {
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                boolean allInit = symbols.stream()
                    .allMatch(sym -> obm.getOrCreateBook("htx", sym).isInitialized());
                if (allInit) break;
                Thread.sleep(500);
            }

            assertTrue(
                symbols.stream().allMatch(sym -> obm.getOrCreateBook("htx", sym).isInitialized()),
                "All books must be initialized within 60s");

            Map<String, Instant> lastUpdateBefore = new HashMap<>();
            for (String sym : symbols)
                lastUpdateBefore.put(sym, obm.getOrCreateBook("htx", sym).getLastUpdateTime());

            Thread.sleep(120_000);

            for (String sym : symbols) {
                Instant before = lastUpdateBefore.get(sym);
                Instant after = obm.getOrCreateBook("htx", sym).getLastUpdateTime();
                assertTrue(after.isAfter(before), sym + " received no updates in 2 minutes");
            }

            assertTrue(client.isConnected());
        } finally {
            client.disconnect();
        }
    }
}
