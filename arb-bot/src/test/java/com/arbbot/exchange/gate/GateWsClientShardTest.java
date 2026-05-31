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
    void parsesSnapshotFromAllEvent() {
        var manager = new OrderBookManager(2000);
        var health = new HealthMonitor(2000);
        var shard = new GateWsClientShard(
            "wss://fx-ws.gateio.ws/v4/ws/usdt",
            Map.of("BTC", "BTC_USDT"), manager, health, new OkHttpClient());

        // Gate.io snapshot: event="all", levels use {p,s} objects
        String snapshot = """
            {"time":1234567890,"channel":"futures.order_book_update","event":"all",
             "result":{"t":1234567890123,"contract":"BTC_USDT","id":93973511,
               "bids":[{"p":"50000.0","s":150},{"p":"49999.0","s":200}],
               "asks":[{"p":"50001.0","s":80},{"p":"50002.0","s":120}]}}
            """;
        shard.onMessage(null, snapshot);

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

        // Apply snapshot first
        String snapshot = """
            {"time":1,"channel":"futures.order_book_update","event":"all",
             "result":{"t":1,"contract":"BTC_USDT","id":100,
               "bids":[{"p":"50000.0","s":150}],
               "asks":[{"p":"50001.0","s":80}]}}
            """;
        shard.onMessage(null, snapshot);

        // Remove the best bid via delta (s=0 means remove)
        String delta = """
            {"time":2,"channel":"futures.order_book_update","event":"update",
             "result":{"t":2,"contract":"BTC_USDT","U":101,"u":101,
               "bids":[{"p":"50000.0","s":0}],
               "asks":[]}}
            """;
        shard.onMessage(null, delta);

        var book = manager.getOrCreateBook("gate", "BTC_USDT");
        assertTrue(book.bestBid().isEmpty(), "Best bid should be removed after s=0 delta");
    }
}
