package com.arbbot.exchange;

import static org.junit.jupiter.api.Assertions.*;
import com.arbbot.exchange.htx.HtxWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import okhttp3.OkHttpClient;
import okio.ByteString;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.GZIPOutputStream;

class HtxWsClientTest {

    private static ByteString gzip(String text) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(buf)) {
            gz.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return ByteString.of(buf.toByteArray());
    }

    @Test
    void nameAndInitialStateAreCorrect() {
        var manager = new OrderBookManager(2000);
        var health = new HealthMonitor(2000);
        var client = new HtxWsClient(
            "wss://api.hbdm.com/linear-swap-ws",
            List.of("BTC-USDT"), manager, health, new OkHttpClient());
        assertEquals("htx", client.name());
        assertFalse(client.isConnected());
    }

    @Test
    void decompressesAndParsesSnapshotFrame() throws Exception {
        var manager = new OrderBookManager(2000);
        var health = new HealthMonitor(2000);
        var client = new HtxWsClient(
            "wss://api.hbdm.com/linear-swap-ws",
            List.of("BTC-USDT"), manager, health, new OkHttpClient());

        // HTX snapshot: tick.event="snapshot", levels are numeric [price, qty]
        String snapshot = """
            {"ch":"market.BTC-USDT.depth.size_20.high_freq","ts":1668144045036,
             "tick":{"ch":"market.BTC-USDT.depth.size_20.high_freq",
               "event":"snapshot","id":1662463050,"mrid":1662463050,
               "ts":1668144045036,"version":6936227,
               "bids":[[50000.0,150.0],[49999.0,200.0]],
               "asks":[[50001.0,80.0],[50002.0,120.0]]}}
            """;
        client.onMessage(null, gzip(snapshot)); // routes through handleBinaryMessage

        var book = manager.getOrCreateBook("htx", "BTC-USDT");
        assertTrue(book.isInitialized());
        assertEquals(50000.0, book.bestBid().orElseThrow(), 0.001);
        assertEquals(50001.0, book.bestAsk().orElseThrow(), 0.001);
    }

    @Test
    void doesNotThrowOnCompressedPingFrame() throws Exception {
        var manager = new OrderBookManager(2000);
        var health = new HealthMonitor(2000);
        var client = new HtxWsClient(
            "wss://api.hbdm.com/linear-swap-ws",
            List.of("BTC-USDT"), manager, health, new OkHttpClient());

        // Server-initiated ping — should decompress and handle without throwing
        // (pong send requires an active connection; here we just verify no exception)
        String pingMsg = "{\"ping\":1668144045036}";
        assertDoesNotThrow(() -> client.onMessage(null, gzip(pingMsg)));
    }
}
