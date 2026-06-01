package com.arbbot.integration;

import com.arbbot.exchange.gate.GateWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted diagnostic for Gate.io WS: confirms snapshot arrives, updates flow for 5 minutes.
 * Run with: ./gradlew test -Dtest.tags=integration -Dtest.filter=GateLiveDiagTest
 */
@Tag("integration")
class GateLiveDiagTest {

    @Test
    @Timeout(value = 7, unit = TimeUnit.MINUTES)
    void gateReceivesSnapshotAndLiveUpdatesFor5Minutes() throws InterruptedException {
        Map<String, String> canonicalToGate = new LinkedHashMap<>();
        canonicalToGate.put("BTC", "BTC_USDT");
        canonicalToGate.put("ETH", "ETH_USDT");
        canonicalToGate.put("SOL", "SOL_USDT");

        var obm = new OrderBookManager(120_000); // 2 min stale threshold for this test
        var health = new HealthMonitor(120_000);
        var client = new GateWsClient(
            "wss://fx-ws.gateio.ws/v4/ws/usdt",
            canonicalToGate, obm, health, new OkHttpClient());

        System.out.println("[diag] Connecting Gate.io...");
        client.connect();

        try {
            // --- Phase 1: wait for all 3 books to initialize (max 90s) ---
            long deadline = System.currentTimeMillis() + 90_000;
            while (System.currentTimeMillis() < deadline) {
                long remaining = (deadline - System.currentTimeMillis()) / 1000;
                boolean allInit = canonicalToGate.values().stream()
                    .allMatch(sym -> obm.getOrCreateBook("gate", sym).isInitialized());
                if (allInit) break;

                // print which ones are still pending
                canonicalToGate.values().forEach(sym -> {
                    var book = obm.getOrCreateBook("gate", sym);
                    if (!book.isInitialized()) {
                        System.out.printf("[diag] %s NOT initialized yet (bid=%s, ask=%s) — %ds left%n",
                            sym,
                            book.bestBid().isPresent() ? String.valueOf(book.bestBid().getAsDouble()) : "none",
                            book.bestAsk().isPresent() ? String.valueOf(book.bestAsk().getAsDouble()) : "none",
                            remaining);
                    }
                });
                Thread.sleep(3000);
            }

            for (String sym : canonicalToGate.values()) {
                var book = obm.getOrCreateBook("gate", sym);
                System.out.printf("[diag] %s initialized=%b bid=%.2f ask=%.2f%n",
                    sym, book.isInitialized(),
                    book.bestBid().orElse(0.0),
                    book.bestAsk().orElse(0.0));
            }

            assertTrue(
                canonicalToGate.values().stream()
                    .allMatch(sym -> obm.getOrCreateBook("gate", sym).isInitialized()),
                "FAIL: books not initialized within 90s");
            System.out.println("[diag] All books initialized. Starting 5-minute liveness check.");

            // --- Phase 2: run for 5 minutes, verify updates keep flowing ---
            long runEnd = System.currentTimeMillis() + 300_000;
            AtomicInteger checkCount = new AtomicInteger(0);
            while (System.currentTimeMillis() < runEnd) {
                Thread.sleep(30_000); // check every 30s
                int check = checkCount.incrementAndGet();
                System.out.printf("[diag] Check #%d (%ds elapsed):%n", check,
                    (int)((300_000 - (runEnd - System.currentTimeMillis())) / 1000));

                for (String sym : canonicalToGate.values()) {
                    var book = obm.getOrCreateBook("gate", sym);
                    long ageMs = Instant.now().toEpochMilli() - book.getLastUpdateTime().toEpochMilli();
                    boolean stale = book.isStale(60_000); // 60s threshold for test
                    System.out.printf("  %s: bid=%.2f ask=%.2f lastUpdateAge=%dms stale=%b%n",
                        sym,
                        book.bestBid().orElse(0.0),
                        book.bestAsk().orElse(0.0),
                        ageMs, stale);
                    assertFalse(stale,
                        "FAIL: " + sym + " went stale after " +
                        ((300_000 - (runEnd - System.currentTimeMillis())) / 1000) + "s");
                }
                assertTrue(client.isConnected(), "FAIL: client disconnected at check #" + check);
            }
            System.out.println("[diag] PASS — Gate.io ran cleanly for 5 minutes.");
        } finally {
            client.disconnect();
        }
    }
}
