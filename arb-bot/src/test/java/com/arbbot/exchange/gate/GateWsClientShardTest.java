package com.arbbot.exchange.gate;

import static org.junit.jupiter.api.Assertions.*;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import java.util.Map;

class GateWsClientShardTest {

    @Test
    void nameAndInitialStateAreCorrect() {
        var manager = new OrderBookManager(2000);
        var health = new HealthMonitor(2000);
        var shard = new GateWsClientShard(
            "wss://fx-ws.gateio.ws/v4/ws/usdt",
            Map.of("BTC", "BTC_USDT"), manager, health, new OkHttpClient());
        assertEquals("gate", shard.name());
        assertFalse(shard.isConnected());
    }

    @Test
    void initializesFromDeltaUpdatesOnce_bothSidesPresent() {
        var manager = new OrderBookManager(2000);
        var health = new HealthMonitor(2000);
        var shard = new GateWsClientShard(
            "wss://fx-ws.gateio.ws/v4/ws/usdt",
            Map.of("BTC", "BTC_USDT"), manager, health, new OkHttpClient());

        // Real Gate.io format: event="update", symbol in result.s, bids in result.b, asks in result.a
        String update = """
            {"time":1234567890,"channel":"futures.order_book_update","event":"update",
             "result":{"t":1234567890123,"U":113857391230,"u":113857391240,"s":"BTC_USDT","l":"20",
               "b":[{"p":"50000.0","s":150},{"p":"49999.0","s":200}],
               "a":[{"p":"50001.0","s":80},{"p":"50002.0","s":120}]}}
            """;
        shard.onMessage(null, update);

        var book = manager.getOrCreateBook("gate", "BTC_USDT");
        assertTrue(book.isInitialized());
        assertEquals(50000.0, book.bestBid().orElseThrow(), 0.001);
        assertEquals(50001.0, book.bestAsk().orElseThrow(), 0.001);
    }

    @Test
    void applyDeltaRemovesLevelWhenSizeZero() {
        var manager = new OrderBookManager(2000);
        var health = new HealthMonitor(2000);
        var shard = new GateWsClientShard(
            "wss://fx-ws.gateio.ws/v4/ws/usdt",
            Map.of("BTC", "BTC_USDT"), manager, health, new OkHttpClient());

        // Initialize with first update that has both sides
        String first = """
            {"time":1,"channel":"futures.order_book_update","event":"update",
             "result":{"t":1,"U":100,"u":100,"s":"BTC_USDT","l":"20",
               "b":[{"p":"50000.0","s":150}],
               "a":[{"p":"50001.0","s":80}]}}
            """;
        shard.onMessage(null, first);

        // Remove the best bid via delta (s=0 means remove)
        String delta = """
            {"time":2,"channel":"futures.order_book_update","event":"update",
             "result":{"t":2,"U":101,"u":101,"s":"BTC_USDT","l":"20",
               "b":[{"p":"50000.0","s":0}],
               "a":[]}}
            """;
        shard.onMessage(null, delta);

        var book = manager.getOrCreateBook("gate", "BTC_USDT");
        assertTrue(book.bestBid().isEmpty(), "Best bid should be removed after s=0 delta");
    }
}
