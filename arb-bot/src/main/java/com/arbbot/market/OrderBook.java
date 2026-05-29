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
    private final AtomicReference<Instant> lastBestBidChangeTime = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> lastBestAskChangeTime = new AtomicReference<>(Instant.EPOCH);
    private volatile double lastKnownBestBid = Double.NaN;
    private volatile double lastKnownBestAsk = Double.NaN;
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
        Instant now = Instant.now();
        lastUpdateTime.set(now);
        lastBestBidChangeTime.set(now);
        lastBestAskChangeTime.set(now);
        lastKnownBestBid = bids.isEmpty() ? Double.NaN : bids.firstKey();
        lastKnownBestAsk = asks.isEmpty() ? Double.NaN : asks.firstKey();
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
        Instant now = Instant.now();
        lastUpdateTime.set(now);
        double newBestBid = bids.isEmpty() ? Double.NaN : bids.firstKey();
        double newBestAsk = asks.isEmpty() ? Double.NaN : asks.firstKey();
        if (Double.compare(newBestBid, lastKnownBestBid) != 0) {
            lastBestBidChangeTime.set(now);
            lastKnownBestBid = newBestBid;
        }
        if (Double.compare(newBestAsk, lastKnownBestAsk) != 0) {
            lastBestAskChangeTime.set(now);
            lastKnownBestAsk = newBestAsk;
        }
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

    public List<PriceLevel> topBids(int n) {
        return bids.entrySet().stream().limit(n)
            .map(e -> new PriceLevel(e.getKey(), e.getValue())).toList();
    }

    public List<PriceLevel> topAsks(int n) {
        return asks.entrySet().stream().limit(n)
            .map(e -> new PriceLevel(e.getKey(), e.getValue())).toList();
    }

    /** Total USD value of the top-n bid levels (best bids). */
    public double bidDepthUsdt(int n) {
        double sum = 0;
        int i = 0;
        for (Map.Entry<Double, Double> e : bids.entrySet()) {
            if (i++ >= n) break;
            sum += e.getKey() * e.getValue();
        }
        return sum;
    }

    /** Total USD value of the top-n ask levels (best asks). */
    public double askDepthUsdt(int n) {
        double sum = 0;
        int i = 0;
        for (Map.Entry<Double, Double> e : asks.entrySet()) {
            if (i++ >= n) break;
            sum += e.getKey() * e.getValue();
        }
        return sum;
    }

    public boolean isStale(long thresholdMs) {
        return !initialized || lastUpdateTime.get().plusMillis(thresholdMs).isBefore(Instant.now());
    }

    /** True if the best bid price hasn't moved in longer than thresholdMs. */
    public boolean isBestBidFrozen(long thresholdMs) {
        return initialized && lastBestBidChangeTime.get().plusMillis(thresholdMs).isBefore(Instant.now());
    }

    /** True if the best ask price hasn't moved in longer than thresholdMs. */
    public boolean isBestAskFrozen(long thresholdMs) {
        return initialized && lastBestAskChangeTime.get().plusMillis(thresholdMs).isBefore(Instant.now());
    }

    public boolean isInitialized() { return initialized; }
    public String getExchange() { return exchange; }
    public String getSymbol() { return symbol; }
    public long getLastSeqNum() { return lastSeqNum.get(); }
    public Instant getLastUpdateTime() { return lastUpdateTime.get(); }
}
