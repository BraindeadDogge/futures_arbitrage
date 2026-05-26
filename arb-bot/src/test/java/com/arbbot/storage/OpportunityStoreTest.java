package com.arbbot.storage;

import static org.junit.jupiter.api.Assertions.*;
import com.arbbot.fees.FundingRate;
import com.arbbot.scanner.Opportunity;
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

    private Opportunity opp(String symbol, String longEx, String shortEx, double net) {
        return new Opportunity(UUID.randomUUID(), symbol, longEx, 50000.0, shortEx, 50200.0,
            0.004, net, 0.002,
            FundingRate.zero(longEx, symbol), FundingRate.zero(shortEx, symbol),
            1000.0, Instant.now());
    }

    @Test
    void savedOpportunityIsPersisted() throws Exception {
        store.save(opp("BTC", "binance", "bybit", 0.002));
        store.flush();
        assertEquals(1, store.queryStats().totalOpportunities());
    }

    @Test
    void batchOf100PersistsAll() throws Exception {
        for (int i = 0; i < 100; i++) {
            store.save(opp("BTC", "binance", "bybit", 0.001 + i * 0.0001));
        }
        store.flush();
        assertEquals(100, store.queryStats().totalOpportunities());
    }

    @Test
    void statsReturnCorrectAverageNetSpread() throws Exception {
        store.save(opp("BTC", "binance", "bybit", 0.002));
        store.save(opp("BTC", "binance", "bybit", 0.004));
        store.flush();
        var stats = store.queryStats();
        assertEquals(0.003, stats.avgNetSpreadPct(), 0.0001);
        assertEquals(0.004, stats.maxNetSpreadPct(), 0.0001);
    }

    @Test
    void statsCountBySymbol() throws Exception {
        store.save(opp("BTC", "binance", "bybit", 0.002));
        store.save(opp("ETH", "binance", "bybit", 0.003));
        store.save(opp("BTC", "binance", "bybit", 0.002));
        store.flush();
        var stats = store.queryStats();
        assertEquals(2, stats.countBySymbol().get("BTC"));
        assertEquals(1, stats.countBySymbol().get("ETH"));
    }
}
