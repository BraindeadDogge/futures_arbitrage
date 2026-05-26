package com.arbbot.util;

/** Token bucket rate limiter. Thread-safe. */
public class RateLimiter {

    private final long capacityTokens;
    private final long refillIntervalMs;
    private long tokens;
    private long lastRefillTime;

    public RateLimiter(long capacityTokens, long refillIntervalMs) {
        this.capacityTokens = capacityTokens;
        this.refillIntervalMs = refillIntervalMs;
        this.tokens = capacityTokens;
        this.lastRefillTime = System.currentTimeMillis();
    }

    /** Returns true if a token was acquired; false if rate limit exceeded. */
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens > 0) {
            tokens--;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillTime;
        if (elapsed >= refillIntervalMs) {
            long toAdd = (elapsed / refillIntervalMs) * capacityTokens;
            tokens = Math.min(capacityTokens, tokens + toAdd);
            lastRefillTime = now;
        }
    }
}
