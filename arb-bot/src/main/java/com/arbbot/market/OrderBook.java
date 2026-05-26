package com.arbbot.market;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class OrderBook {

    public record PriceLevel(double price, double qty) {}

    private final String exchange;
    private final String symbol;
    private final ConcurrentSkipListMap<Double, Double> bids =
        new ConcurrentSkipListMap<>(Comparator.reverseOrder());
    private final ConcurrentSkipListMap<Double, Double> asks =
        new ConcurrentSkipListMap<>();
    private final AtomicLong lastSeqNum = new AtomicLong(-1);
    private final AtomicReference<Instant> lastUpdateTime = new AtomicReference<>(Instant.EPOCH);
    private volatile boolean initialized = false;

    public OrderBook(String exchange, String symbol) {
        this.exchange = exchange;
        this.symbol = symbol;
    }

    public synchronized void applySnapshot(Map<Double, Double> newBids, Map<Double, Double> newAsks, long seqNum) {
        bids.clear();
        bids.putAll(newBids);
        asks.clear();
        asks.putAll(newAsks);
        lastSeqNum.set(seqNum);
        lastUpdateTime.set(Instant.now());
        initialized = true;
    }

    /**
     * Returns false if a sequence gap is detected (caller must trigger re-sync).
     * Pass seqNum=-1 to skip sequence validation (Binance U/u range is checked externally).
     */
    public synchronized boolean applyDelta(List<PriceLevel> bidDeltas, List<PriceLevel> askDeltas, long seqNum) {
        if (!initialized) return false;
        if (seqNum != -1 && lastSeqNum.get() != -1) {
            long expected = lastSeqNum.get() + 1;
            if (seqNum != expected) {
                initialized = false;
                return false;
            }
        }
        applyLevels(bids, bidDeltas);
        applyLevels(asks, askDeltas);
        if (seqNum != -1) lastSeqNum.set(seqNum);
        lastUpdateTime.set(Instant.now());
        return true;
    }

    private void applyLevels(ConcurrentSkipListMap<Double, Double> side, List<PriceLevel> deltas) {
        for (PriceLevel level : deltas) {
            if (level.qty() <= 0.0) {
                side.remove(level.price());
            } else {
                side.put(level.price(), level.qty());
            }
        }
    }

    public OptionalDouble effectiveBuyPrice(double notionalUsdt) {
        double remaining = notionalUsdt;
        double totalQty = 0.0;
        for (Map.Entry<Double, Double> entry : asks.entrySet()) {
            double price = entry.getKey();
            double qty = entry.getValue();
            double levelValue = price * qty;
            if (levelValue >= remaining) {
                totalQty += remaining / price;
                remaining = 0;
                break;
            }
            totalQty += qty;
            remaining -= levelValue;
        }
        if (remaining > 0 || totalQty == 0) return OptionalDouble.empty();
        return OptionalDouble.of(notionalUsdt / totalQty);
    }

    public OptionalDouble effectiveSellPrice(double notionalUsdt) {
        double remaining = notionalUsdt;
        double totalQty = 0.0;
        for (Map.Entry<Double, Double> entry : bids.entrySet()) {
            double price = entry.getKey();
            double qty = entry.getValue();
            double levelValue = price * qty;
            if (levelValue >= remaining) {
                totalQty += remaining / price;
                remaining = 0;
                break;
            }
            totalQty += qty;
            remaining -= levelValue;
        }
        if (remaining > 0 || totalQty == 0) return OptionalDouble.empty();
        return OptionalDouble.of(notionalUsdt / totalQty);
    }

    public OptionalDouble bestBid() {
        return bids.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(bids.firstKey());
    }

    public OptionalDouble bestAsk() {
        return asks.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(asks.firstKey());
    }

    public boolean isStale(long thresholdMs) {
        return !initialized || lastUpdateTime.get().plusMillis(thresholdMs).isBefore(Instant.now());
    }

    public boolean isInitialized() { return initialized; }
    public String getExchange() { return exchange; }
    public String getSymbol() { return symbol; }
    public long getLastSeqNum() { return lastSeqNum.get(); }
    public Instant getLastUpdateTime() { return lastUpdateTime.get(); }
}
