package com.arbbot.scanner;

import com.arbbot.fees.FeeEngine;
import com.arbbot.market.OrderBook;
import com.arbbot.market.Tick;
import java.util.OptionalDouble;

public final class SpreadCalculator {

    private SpreadCalculator() {}

    /** Raw gross spread fraction: (sellPrice - buyPrice) / buyPrice */
    public static double grossSpread(Tick buyTick, Tick sellTick) {
        double buyPrice = buyTick.effectiveBuyPrice();
        double sellPrice = sellTick.effectiveSellPrice();
        if (buyPrice <= 0 || sellPrice <= 0) return Double.NEGATIVE_INFINITY;
        return (sellPrice - buyPrice) / buyPrice;
    }

    /** Net spread after subtracting total round-trip cost (fees + estimated funding). */
    public static double netSpread(Tick buyTick, Tick sellTick, FeeEngine feeEngine, double holdingHours) {
        double gross = grossSpread(buyTick, sellTick);
        if (!Double.isFinite(gross)) return Double.NEGATIVE_INFINITY;
        double cost = feeEngine.getTotalRoundTripCost(
            buyTick.canonicalSymbol(), buyTick.exchange(),
            sellTick.canonicalSymbol(), sellTick.exchange(),
            holdingHours);
        return gross - cost;
    }

    /**
     * Binary-searches for the largest USDT notional where
     * (effectiveSell - effectiveBuy) / effectiveBuy > costFraction.
     * Returns 0 if no profitable volume exists at any size.
     */
    public static double maxProfitableVolume(OrderBook longBook, OrderBook shortBook, double costFraction) {
        OptionalDouble buy0 = longBook.effectiveBuyPrice(100);
        OptionalDouble sell0 = shortBook.effectiveSellPrice(100);
        if (buy0.isEmpty() || sell0.isEmpty()) return 0;
        if ((sell0.getAsDouble() - buy0.getAsDouble()) / buy0.getAsDouble() <= costFraction) return 0;
        double lo = 0, hi = 500_000;
        for (int iter = 0; iter < 25; iter++) {
            double mid = (lo + hi) / 2;
            OptionalDouble buy = longBook.effectiveBuyPrice(mid);
            OptionalDouble sell = shortBook.effectiveSellPrice(mid);
            if (buy.isEmpty() || sell.isEmpty()) { hi = mid; continue; }
            if ((sell.getAsDouble() - buy.getAsDouble()) / buy.getAsDouble() > costFraction) lo = mid;
            else hi = mid;
        }
        return lo;
    }
}
