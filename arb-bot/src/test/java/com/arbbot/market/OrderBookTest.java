package com.arbbot.market;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

class OrderBookTest {

    @Test
    void notInitializedBeforeSnapshot() {
        var book = new OrderBook("binance", "BTCUSDT");
        assertFalse(book.isInitialized());
    }

    @Test
    void initializedAfterSnapshot() {
        var book = new OrderBook("binance", "BTCUSDT");
        book.applySnapshot(Map.of(50000.0, 1.0), Map.of(50001.0, 0.5), 100L);
        assertTrue(book.isInitialized());
    }

    @Test
    void bestBidAndAskAfterSnapshot() {
        var book = new OrderBook("binance", "BTCUSDT");
        book.applySnapshot(
            Map.of(50000.0, 1.0, 49999.0, 2.0),
            Map.of(50001.0, 0.5, 50002.0, 1.0),
            100L);
        assertEquals(50000.0, book.bestBid().orElseThrow(), 0.001);
        assertEquals(50001.0, book.bestAsk().orElseThrow(), 0.001);
    }

    @Test
    void deltaAddsLevel() {
        var book = new OrderBook("binance", "BTCUSDT");
        book.applySnapshot(Map.of(50000.0, 1.0), Map.of(50001.0, 0.5), 100L);
        book.applyDelta(
            List.of(new OrderBook.PriceLevel(49998.0, 3.0)),
            List.of(),
            -1L);
        assertEquals(50000.0, book.bestBid().orElseThrow(), 0.001);
    }

    @Test
    void deltaRemovesLevelWhenQtyZero() {
        var book = new OrderBook("binance", "BTCUSDT");
        book.applySnapshot(Map.of(50000.0, 1.0), Map.of(50001.0, 0.5), 100L);
        book.applyDelta(
            List.of(new OrderBook.PriceLevel(50000.0, 0.0)),
            List.of(),
            -1L);
        assertTrue(book.bestBid().isEmpty());
    }

    @Test
    void deltaUpdatesExistingLevel() {
        var book = new OrderBook("binance", "BTCUSDT");
        book.applySnapshot(Map.of(50000.0, 1.0), Map.of(50001.0, 0.5), 100L);
        book.applyDelta(
            List.of(new OrderBook.PriceLevel(50000.0, 2.5)),
            List.of(),
            -1L);
        assertEquals(50000.0, book.bestBid().orElseThrow(), 0.001);
    }

    @Test
    void snapshotReplacesFullBook() {
        var book = new OrderBook("binance", "BTCUSDT");
        book.applySnapshot(Map.of(50000.0, 1.0), Map.of(50001.0, 0.5), 100L);
        book.applySnapshot(Map.of(51000.0, 2.0), Map.of(51001.0, 1.0), 200L);
        assertEquals(51000.0, book.bestBid().orElseThrow(), 0.001);
        assertEquals(51001.0, book.bestAsk().orElseThrow(), 0.001);
    }
}
