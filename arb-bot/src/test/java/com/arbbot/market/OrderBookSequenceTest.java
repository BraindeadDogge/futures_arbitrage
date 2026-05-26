package com.arbbot.market;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

class OrderBookSequenceTest {

    @Test
    void sequentialDeltasApplied() {
        var book = new OrderBook("kucoin", "BTCUSDTM");
        book.applySnapshot(Map.of(50000.0, 1.0), Map.of(50001.0, 0.5), 100L);
        assertTrue(book.applyDelta(List.of(), List.of(new OrderBook.PriceLevel(50001.0, 1.0)), 101L));
        assertTrue(book.applyDelta(List.of(), List.of(new OrderBook.PriceLevel(50001.0, 2.0)), 102L));
    }

    @Test
    void gapInSequenceReturnsFalseAndMarksUninitialized() {
        var book = new OrderBook("kucoin", "BTCUSDTM");
        book.applySnapshot(Map.of(50000.0, 1.0), Map.of(50001.0, 0.5), 100L);
        assertTrue(book.applyDelta(List.of(), List.of(), 101L));
        // Skip 102, jump to 103 — gap detected
        assertFalse(book.applyDelta(List.of(), List.of(), 103L));
        assertFalse(book.isInitialized());
    }

    @Test
    void skipGapCheckWhenSeqIsMinusOne() {
        var book = new OrderBook("binance", "BTCUSDT");
        book.applySnapshot(Map.of(50000.0, 1.0), Map.of(50001.0, 0.5), 100L);
        // -1 skips sequence validation (Binance's U/u range is checked externally)
        assertTrue(book.applyDelta(List.of(), List.of(), -1L));
    }
}
