package com.arbbot.integration;

import com.arbbot.exchange.kucoin.KuCoinWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class KuCoinIntegrationTest {

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void allBooksInitializeAndReceiveUpdates() throws InterruptedException {
        // KuCoin futures uses "XBTUSDTM" for BTC, "ETHUSDTM" for ETH, "SOLUSDT" for SOL
        Map<String, String> canonicalToKucoin = new LinkedHashMap<>();
        canonicalToKucoin.put("BTC", "XBTUSDTM");
        canonicalToKucoin.put("ETH", "ETHUSDTM");
        canonicalToKucoin.put("SOL", "SOLUSDT");

        var obm = new OrderBookManager(5000);
        var health = new HealthMonitor(5000);
        var client = new KuCoinWsClient(
            "https://api-futures.kucoin.com",
            canonicalToKucoin, obm, health, new OkHttpClient());

        assertEquals(1, client.shardCount());

        client.connect();
        try {
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                boolean allInit = canonicalToKucoin.values().stream()
                    .allMatch(sym -> obm.getOrCreateBook("kucoin", sym).isInitialized());
                if (allInit) break;
                Thread.sleep(500);
            }

            assertTrue(
                canonicalToKucoin.values().stream()
                    .allMatch(sym -> obm.getOrCreateBook("kucoin", sym).isInitialized()),
                "All books must be initialized within 60s");

            Map<String, Instant> lastUpdateBefore = new HashMap<>();
            for (String sym : canonicalToKucoin.values())
                lastUpdateBefore.put(sym, obm.getOrCreateBook("kucoin", sym).getLastUpdateTime());

            Thread.sleep(120_000);

            for (String sym : canonicalToKucoin.values()) {
                Instant before = lastUpdateBefore.get(sym);
                Instant after = obm.getOrCreateBook("kucoin", sym).getLastUpdateTime();
                assertTrue(after.isAfter(before), sym + " received no updates in 2 minutes");
            }

            assertTrue(client.isConnected());
        } finally {
            client.disconnect();
        }
    }
}
