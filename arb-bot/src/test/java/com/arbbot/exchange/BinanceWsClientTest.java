package com.arbbot.exchange;

import static org.junit.jupiter.api.Assertions.*;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import com.arbbot.exchange.binance.BinanceWsClient;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import java.util.List;

class BinanceWsClientTest {

    private MockWebServer mockServer;
    private OrderBookManager manager;
    private HealthMonitor health;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        manager = new OrderBookManager(2000);
        health = new HealthMonitor(2000);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockServer.shutdown();
    }

    @Test
    void parsesSnapshotCorrectly() throws Exception {
        mockServer.enqueue(new MockResponse()
            .setBody("{\"lastUpdateId\":500,\"bids\":[[\"50000.0\",\"2.5\"],[\"49999.0\",\"1.0\"]],\"asks\":[[\"50001.0\",\"1.5\"]]}")
            .addHeader("Content-Type", "application/json"));

        String restUrl = mockServer.url("").toString().replaceAll("/$", "");
        var client = new BinanceWsClient(
            "ws://localhost:1", restUrl, List.of("BTCUSDT"),
            manager, health, new OkHttpClient());

        client.fetchSnapshotForTest("BTCUSDT");
        Thread.sleep(500);

        var book = manager.getOrCreateBook("binance", "BTCUSDT");
        assertTrue(book.isInitialized());
        assertEquals(50000.0, book.bestBid().orElseThrow(), 0.001);
        assertEquals(50001.0, book.bestAsk().orElseThrow(), 0.001);
        assertEquals(500L, book.getLastSeqNum());
    }

    @Test
    void snapshotFetchUsesCorrectEndpoint() throws Exception {
        mockServer.enqueue(new MockResponse()
            .setBody("{\"lastUpdateId\":100,\"bids\":[[\"50000.0\",\"1.0\"]],\"asks\":[[\"50001.0\",\"0.5\"]]}")
            .addHeader("Content-Type", "application/json"));

        String restUrl = mockServer.url("").toString().replaceAll("/$", "");
        var client = new BinanceWsClient(
            "ws://localhost:1", restUrl, List.of("BTCUSDT"),
            manager, health, new OkHttpClient());

        client.fetchSnapshotForTest("BTCUSDT");
        Thread.sleep(500);

        var request = mockServer.takeRequest();
        assertTrue(request.getPath().contains("/fapi/v1/depth"));
        assertTrue(request.getPath().contains("symbol=BTCUSDT"));
        assertTrue(request.getPath().contains("limit=200"));
    }
}
