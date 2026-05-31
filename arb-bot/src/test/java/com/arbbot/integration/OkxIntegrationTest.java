package com.arbbot.integration;

import com.arbbot.exchange.okx.OkxWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class OkxIntegrationTest {

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void allBooksInitializeAndReceiveUpdates() throws InterruptedException {
        // OKX uses "BTC-USDT-SWAP" format
        Map<String, String> canonicalToOkx = new LinkedHashMap<>();
        canonicalToOkx.put("BTC", "BTC-USDT-SWAP");
        canonicalToOkx.put("ETH", "ETH-USDT-SWAP");
        canonicalToOkx.put("SOL", "SOL-USDT-SWAP");

        var obm = new OrderBookManager(5000);
        var health = new HealthMonitor(5000);
        var client = new OkxWsClient(
            "wss://ws.okx.com:8443/ws/v5/public",
            canonicalToOkx, obm, health, new OkHttpClient());

        assertEquals(1, client.shardCount());

        client.connect();
        try {
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                boolean allInit = canonicalToOkx.values().stream()
                    .allMatch(sym -> obm.getOrCreateBook("okx", sym).isInitialized());
                if (allInit) break;
                Thread.sleep(500);
            }

            assertTrue(
                canonicalToOkx.values().stream()
                    .allMatch(sym -> obm.getOrCreateBook("okx", sym).isInitialized()),
                "All books must be initialized within 60s");

            Map<String, Instant> lastUpdateBefore = new HashMap<>();
            for (String sym : canonicalToOkx.values())
                lastUpdateBefore.put(sym, obm.getOrCreateBook("okx", sym).getLastUpdateTime());

            Thread.sleep(120_000);

            for (String sym : canonicalToOkx.values()) {
                Instant before = lastUpdateBefore.get(sym);
                Instant after = obm.getOrCreateBook("okx", sym).getLastUpdateTime();
                assertTrue(after.isAfter(before), sym + " received no updates in 2 minutes");
            }

            assertTrue(client.isConnected());
        } finally {
            client.disconnect();
        }
    }
}
