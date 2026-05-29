package com.arbbot.storage;

import static org.junit.jupiter.api.Assertions.*;
import com.arbbot.fees.FundingRate;
import com.arbbot.scanner.Opportunity;
import org.junit.jupiter.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

class OpportunityStoreConcurrencyTest {

    private Path dbFile;
    private OpportunityStore store;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("test-concurrent", ".db");
        store = new OpportunityStore(dbFile.toString(), 10_000);
        store.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
        Files.deleteIfExists(dbFile);
    }

    @Test
    void concurrent10ThreadsNoDataLoss() throws Exception {
        int threads = 10, perThread = 20;
        List<Thread> writers = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            writers.add(Thread.ofVirtual().start(() -> {
                try {
                    latch.await();
                    for (int i = 0; i < perThread; i++) {
                        store.save(new Opportunity(UUID.randomUUID(), "BTC", "binance", 50000, 49900,
                            "bybit", 50200, 50300, 0.004, 0.002, 0.002,
                            FundingRate.zero("binance", "BTC"), FundingRate.zero("bybit", "BTC"),
                            1000.0, 5000.0, 200_000.0, 150_000.0, Instant.now()));
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
        }
        latch.countDown();
        for (Thread t : writers) t.join(5000);
        store.flush();

        assertEquals(threads * perThread, store.queryStats().totalOpportunities());
    }
}
