package com.arbbot.storage;

import static org.junit.jupiter.api.Assertions.*;
import com.arbbot.storage.OpportunityStore.OpportunitySession;
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
                        Instant now = Instant.now();
                        store.saveSession(new OpportunitySession(
                            UUID.randomUUID().toString(), "BTC", "binance", "bybit",
                            now.minusSeconds(10), now,
                            0.2, 0.2, 0.2, 0.2, 0.2,
                            10_000.0, 8_000.0, 10_000L, 100));
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
