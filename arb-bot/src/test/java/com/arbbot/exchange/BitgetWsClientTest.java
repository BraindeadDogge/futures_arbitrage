package com.arbbot.exchange;

import static org.junit.jupiter.api.Assertions.*;
import com.arbbot.exchange.bitget.BitgetWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import java.util.List;

class BitgetWsClientTest {

    @Test
    void nameAndInitialStateAreCorrect() {
        var manager = new OrderBookManager(2000);
        var health = new HealthMonitor(2000);
        var client = new BitgetWsClient(
            "wss://ws.bitget.com/v2/ws/public",
            List.of("BTCUSDT"), manager, health, new OkHttpClient());
        assertEquals("bitget", client.name());
        assertFalse(client.isConnected());
    }

    @Test
    void parsesSnapshotFromActionSnapshot() {
        var manager = new OrderBookManager(2000);
        var health = new HealthMonitor(2000);
        var client = new BitgetWsClient(
            "wss://ws.bitget.com/v2/ws/public",
            List.of("BTCUSDT"), manager, health, new OkHttpClient());

        // Bitget snapshot: action="snapshot", levels are [price_string, qty_string]
        String snapshot = """
            {"action":"snapshot",
             "arg":{"instType":"USDT-FUTURES","channel":"books","instId":"BTCUSDT"},
             "data":[{
               "asks":[["50001.0","0.8"],["50002.0","1.2"]],
               "bids":[["50000.0","1.5"],["49999.0","2.0"]],
               "checksum":-855196932,"seq":1000001,"ts":"1695716059516"
             }],"ts":1695716059516}
            """;
        client.onMessage(null, snapshot);

        var book = manager.getOrCreateBook("bitget", "BTCUSDT");
        assertTrue(book.isInitialized());
        assertEquals(50000.0, book.bestBid().orElseThrow(), 0.001);
        assertEquals(50001.0, book.bestAsk().orElseThrow(), 0.001);
    }

    @Test
    void applyDeltaRemovesLevelWhenQtyZero() {
        var manager = new OrderBookManager(2000);
        var health = new HealthMonitor(2000);
        var client = new BitgetWsClient(
            "wss://ws.bitget.com/v2/ws/public",
            List.of("BTCUSDT"), manager, health, new OkHttpClient());

        String snapshot = """
            {"action":"snapshot",
             "arg":{"instType":"USDT-FUTURES","channel":"books","instId":"BTCUSDT"},
             "data":[{"asks":[["50001.0","0.8"]],"bids":[["50000.0","1.5"]],
                      "checksum":0,"seq":100,"ts":"1"}],"ts":1}
            """;
        client.onMessage(null, snapshot);

        // Remove best ask via qty=0
        String delta = """
            {"action":"update",
             "arg":{"instType":"USDT-FUTURES","channel":"books","instId":"BTCUSDT"},
             "data":[{"asks":[["50001.0","0"]],"bids":[],"checksum":0,"seq":101,"ts":"2"}],"ts":2}
            """;
        client.onMessage(null, delta);

        var book = manager.getOrCreateBook("bitget", "BTCUSDT");
        assertTrue(book.bestAsk().isEmpty(), "Best ask should be removed after qty=0 delta");
    }
}
