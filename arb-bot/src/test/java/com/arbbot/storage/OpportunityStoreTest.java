package com.arbbot.storage;

import static org.junit.jupiter.api.Assertions.*;
import com.arbbot.storage.OpportunityStore.OpportunitySession;
import org.junit.jupiter.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

class OpportunityStoreTest {

    private Path dbFile;
    private OpportunityStore store;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("test-opps", ".db");
        store = new OpportunityStore(dbFile.toString(), 10_000);
        store.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
        Files.deleteIfExists(dbFile);
    }

    private OpportunitySession session(String symbol, String longEx, String shortEx,
                                       double avgNetPct, double peakNetPct) {
        Instant now = Instant.now();
        return new OpportunitySession(
            UUID.randomUUID().toString(), symbol, longEx, shortEx,
            now.minusSeconds(10), now,
            peakNetPct, avgNetPct, avgNetPct,
            avgNetPct, avgNetPct,
            10_000.0, 8_000.0,
            10_000L, 100);
    }

    @Test
    void savedOpportunityIsPersisted() throws Exception {
        store.saveSession(session("BTC", "binance", "bybit", 0.2, 0.2));
        store.flush();
        assertEquals(1, store.queryStats().totalOpportunities());
    }

    @Test
    void batchOf100PersistsAll() throws Exception {
        for (int i = 0; i < 100; i++) {
            store.saveSession(session("BTC", "binance", "bybit", 0.1 + i * 0.001, 0.2));
        }
        store.flush();
        assertEquals(100, store.queryStats().totalOpportunities());
    }

    @Test
    void statsReturnCorrectAverageNetSpread() throws Exception {
        store.saveSession(session("BTC", "binance", "bybit", 0.2, 0.2));
        store.saveSession(session("BTC", "binance", "bybit", 0.4, 0.4));
        store.flush();
        var stats = store.queryStats();
        assertEquals(0.3, stats.avgNetSpreadPct(), 0.0001);
        assertEquals(0.4, stats.maxNetSpreadPct(), 0.0001);
    }

    @Test
    void statsCountBySymbol() throws Exception {
        store.saveSession(session("BTC", "binance", "bybit", 0.2, 0.2));
        store.saveSession(session("ETH", "binance", "bybit", 0.3, 0.3));
        store.saveSession(session("BTC", "binance", "bybit", 0.2, 0.2));
        store.flush();
        var stats = store.queryStats();
        assertEquals(2, stats.countBySymbol().get("BTC"));
        assertEquals(1, stats.countBySymbol().get("ETH"));
    }
}
