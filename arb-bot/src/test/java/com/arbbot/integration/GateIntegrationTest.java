package com.arbbot.integration;

import com.arbbot.exchange.gate.GateWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class GateIntegrationTest {

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void allBooksInitializeAndReceiveUpdates() throws InterruptedException {
        // Gate.io uses "BTC_USDT" format
        Map<String, String> canonicalToGate = new LinkedHashMap<>();
        canonicalToGate.put("BTC", "BTC_USDT");
        canonicalToGate.put("ETH", "ETH_USDT");
        canonicalToGate.put("SOL", "SOL_USDT");

        var obm = new OrderBookManager(5000);
        var health = new HealthMonitor(5000);
        var client = new GateWsClient(
            "wss://fx-ws.gateio.ws/v4/ws/usdt",
            canonicalToGate, obm, health, new OkHttpClient());

        assertEquals(1, client.shardCount());

        client.connect();
        try {
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                boolean allInit = canonicalToGate.values().stream()
                    .allMatch(sym -> obm.getOrCreateBook("gate", sym).isInitialized());
                if (allInit) break;
                Thread.sleep(500);
            }

            assertTrue(
                canonicalToGate.values().stream()
                    .allMatch(sym -> obm.getOrCreateBook("gate", sym).isInitialized()),
                "All books must be initialized within 60s");

            Map<String, Instant> lastUpdateBefore = new HashMap<>();
            for (String sym : canonicalToGate.values())
                lastUpdateBefore.put(sym, obm.getOrCreateBook("gate", sym).getLastUpdateTime());

            Thread.sleep(120_000);

            for (String sym : canonicalToGate.values()) {
                Instant before = lastUpdateBefore.get(sym);
                Instant after = obm.getOrCreateBook("gate", sym).getLastUpdateTime();
                assertTrue(after.isAfter(before), sym + " received no updates in 2 minutes");
            }

            assertTrue(client.isConnected());
        } finally {
            client.disconnect();
        }
    }
}
