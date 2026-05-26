package com.arbbot.exchange;

import static org.junit.jupiter.api.Assertions.*;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import com.arbbot.exchange.bybit.BybitWsClient;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import java.util.List;

class BybitWsClientTest {

    @Test
    void nameAndInitialStateAreCorrect() {
        var manager = new OrderBookManager(2000);
        var health = new HealthMonitor(2000);
        var client = new BybitWsClient(
            "wss://stream.bybit.com/v5/public/linear",
            List.of("BTCUSDT"), manager, health, new OkHttpClient());
        assertEquals("bybit", client.name());
        assertFalse(client.isConnected());
    }

    @Test
    void parsesSnapshotFromMessage() throws Exception {
        var manager = new OrderBookManager(2000);
        var health = new HealthMonitor(2000);
        var client = new BybitWsClient(
            "wss://stream.bybit.com/v5/public/linear",
            List.of("BTCUSDT"), manager, health, new OkHttpClient());

        // Simulate a snapshot message being received
        String snapshotMsg = """
            {"topic":"orderbook.50.BTCUSDT","type":"snapshot","ts":1234567890,
             "data":{"s":"BTCUSDT","b":[["50000.0","1.5"],["49999.0","2.0"]],
                     "a":[["50001.0","0.8"],["50002.0","1.2"]],"seq":12345,"u":1}}
            """;
        client.onMessage(null, snapshotMsg);

        var book = manager.getOrCreateBook("bybit", "BTCUSDT");
        assertTrue(book.isInitialized());
        assertEquals(50000.0, book.bestBid().orElseThrow(), 0.001);
        assertEquals(50001.0, book.bestAsk().orElseThrow(), 0.001);
        assertEquals(12345L, book.getLastSeqNum());
    }
}
