package com.arbbot.integration;

import com.arbbot.exchange.bybit.BybitWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class BybitIntegrationTest {

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void allShardsInitializeAndReceiveUpdates() throws InterruptedException {
        // 15 symbols forces 2 shards (limit=10)
        List<String> symbols = List.of(
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT",
            "DOGEUSDT", "LINKUSDT", "AVAXUSDT", "ADAUSDT", "DOTUSDT",
            "LTCUSDT", "TRXUSDT", "SUIUSDT", "APTUSDT", "NEARUSDT");

        var obm = new OrderBookManager(5000);
        var health = new HealthMonitor(5000);
        var client = new BybitWsClient(
            "wss://stream.bybit.com/v5/public/linear", symbols, obm, health, new OkHttpClient());

        assertEquals(2, client.shardCount(), "15 symbols / 10 per shard = 2 shards");

        client.connect();
        try {
            // Wait for all books to initialize (max 60s)
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                boolean allInit = symbols.stream()
                    .allMatch(sym -> obm.getOrCreateBook("bybit", sym).isInitialized());
                if (allInit) break;
                Thread.sleep(500);
            }

            assertTrue(
                symbols.stream().allMatch(sym -> obm.getOrCreateBook("bybit", sym).isInitialized()),
                "All 15 books must be initialized within 60s");

            // Record update times, wait 2 minutes, verify each book received at least one update
            Map<String, Instant> lastUpdateBefore = new HashMap<>();
            for (String sym : symbols)
                lastUpdateBefore.put(sym, obm.getOrCreateBook("bybit", sym).getLastUpdateTime());

            Thread.sleep(120_000);

            for (String sym : symbols) {
                Instant before = lastUpdateBefore.get(sym);
                Instant after = obm.getOrCreateBook("bybit", sym).getLastUpdateTime();
                assertTrue(after.isAfter(before), sym + " received no updates in 2 minutes — shard may be dead");
            }

            assertTrue(client.isConnected(), "At least one shard must still be connected");
        } finally {
            client.disconnect();
        }
    }
}
