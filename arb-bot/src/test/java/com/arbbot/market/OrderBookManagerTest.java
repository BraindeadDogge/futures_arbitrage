package com.arbbot.market;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

class OrderBookManagerTest {

    @Test
    void returnsTickForKnownBook() {
        var manager = new OrderBookManager(2000);
        manager.getOrCreateBook("binance", "BTCUSDT")
            .applySnapshot(Map.of(50000.0, 2.0), Map.of(50001.0, 2.0), 1L);

        var tick = manager.getTick("binance", "BTCUSDT", "BTC", 1000.0);
        assertTrue(tick.isPresent());
        assertTrue(tick.get().isReliable());
        assertEquals("binance", tick.get().exchange());
        assertEquals("BTC", tick.get().canonicalSymbol());
    }

    @Test
    void tickIsUnreliableWhenBookNotInitialized() {
        var manager = new OrderBookManager(2000);
        manager.getOrCreateBook("binance", "BTCUSDT");

        var tick = manager.getTick("binance", "BTCUSDT", "BTC", 1000.0);
        assertTrue(tick.isPresent());
        assertFalse(tick.get().isReliable());
    }

    @Test
    void getAllTicksForSymbolReturnsOnePerExchange() {
        var manager = new OrderBookManager(2000);
        manager.getOrCreateBook("binance", "BTCUSDT")
            .applySnapshot(Map.of(50000.0, 2.0), Map.of(50001.0, 2.0), 1L);
        manager.getOrCreateBook("bybit", "BTCUSDT")
            .applySnapshot(Map.of(50005.0, 2.0), Map.of(50006.0, 2.0), 1L);

        var ticks = manager.getAllTicks("BTC", Map.of("binance", "BTCUSDT", "bybit", "BTCUSDT"), 1000.0);
        assertEquals(2, ticks.size());
    }

    @Test
    void resyncRequiredReturnsTrueOnGap() {
        var manager = new OrderBookManager(2000);
        var book = manager.getOrCreateBook("kucoin", "BTCUSDTM");
        book.applySnapshot(Map.of(50000.0, 2.0), Map.of(50001.0, 2.0), 100L);
        boolean gapped = !book.applyDelta(List.of(), List.of(), 102L); // gap: skip 101
        assertTrue(gapped);
        assertFalse(book.isInitialized());
    }
}
