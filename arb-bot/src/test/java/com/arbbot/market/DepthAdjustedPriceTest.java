package com.arbbot.market;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.Map;

class DepthAdjustedPriceTest {

    @Test
    void buyPriceWalksAskSide() {
        var book = new OrderBook("binance", "BTCUSDT");
        // Asks: 50001 → 0.5 BTC ($25000.5), 50002 → 1.0 BTC ($50002)
        book.applySnapshot(
            Map.of(50000.0, 10.0),
            Map.of(50001.0, 0.5, 50002.0, 1.0),
            1L);

        // Buy $1000: level 50001 has 0.5 BTC = $25000.50 → more than $1000
        double price = book.effectiveBuyPrice(1000.0).orElseThrow();
        assertEquals(50001.0, price, 0.01);
    }

    @Test
    void buyPriceAveragedAcrossMultipleLevels() {
        var book = new OrderBook("binance", "BTCUSDT");
        // Asks: 50001 → 0.01 BTC ($500.01), 50002 → 0.01 BTC ($500.02)
        book.applySnapshot(
            Map.of(50000.0, 10.0),
            Map.of(50001.0, 0.01, 50002.0, 0.01),
            1L);

        // Buy $1000: takes all of level 50001 ($500.01) + rest from 50002
        double price = book.effectiveBuyPrice(1000.0).orElseThrow();
        assertTrue(price > 50001.0 && price < 50003.0,
            "Expected price between 50001 and 50003, got " + price);
    }

    @Test
    void returnsEmptyWhenInsufficientDepth() {
        var book = new OrderBook("binance", "BTCUSDT");
        book.applySnapshot(
            Map.of(50000.0, 10.0),
            Map.of(50001.0, 0.001),  // only $50 of depth
            1L);
        assertTrue(book.effectiveBuyPrice(1000.0).isEmpty());
    }

    @Test
    void sellPriceWalksBidSide() {
        var book = new OrderBook("binance", "BTCUSDT");
        // Bids: 50000 → 0.5 BTC ($25000), 49999 → 1.0 BTC
        book.applySnapshot(
            Map.of(50000.0, 0.5, 49999.0, 1.0),
            Map.of(50001.0, 1.0),
            1L);

        // Sell $1000: level 50000 has 0.5 BTC = $25000 → enough
        double price = book.effectiveSellPrice(1000.0).orElseThrow();
        assertEquals(50000.0, price, 0.01);
    }

    @Test
    void emptyBookReturnsBestBidAskEmpty() {
        var book = new OrderBook("binance", "BTCUSDT");
        assertTrue(book.bestBid().isEmpty());
        assertTrue(book.bestAsk().isEmpty());
    }
}
