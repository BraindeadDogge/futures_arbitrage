package com.arbbot.util;

import java.util.concurrent.atomic.AtomicLong;

/** Token bucket rate limiter. Thread-safe. */
public class RateLimiter {

    private final long capacityTokens;
    private final long refillIntervalMs;
    private final AtomicLong tokens;
    private volatile long lastRefillTime;

    public RateLimiter(long capacityTokens, long refillIntervalMs) {
        this.capacityTokens = capacityTokens;
        this.refillIntervalMs = refillIntervalMs;
        this.tokens = new AtomicLong(capacityTokens);
        this.lastRefillTime = System.currentTimeMillis();
    }

    /** Returns true if a token was acquired; false if rate limit exceeded. */
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens.get() > 0) {
            tokens.decrementAndGet();
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillTime;
        if (elapsed >= refillIntervalMs) {
            long toAdd = (elapsed / refillIntervalMs) * capacityTokens;
            tokens.set(Math.min(capacityTokens, tokens.get() + toAdd));
            lastRefillTime = now;
        }
    }
}
