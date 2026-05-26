package com.arbbot.health;

import com.arbbot.scanner.Opportunity;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class MetricsRegistry {

    private static final Logger log = LoggerFactory.getLogger(MetricsRegistry.class);

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> exchangeHealth = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> clockOffsets = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastNetSpread = new ConcurrentHashMap<>();

    public MetricsRegistry(MeterRegistry registry) {
        this.registry = registry;
    }

    public static MetricsRegistry createSimple() {
        return new MetricsRegistry(new SimpleMeterRegistry());
    }

    public void recordTick(String exchange, String symbol) {
        Counter.builder("arb.ticks.received")
            .tag("exchange", exchange).tag("symbol", symbol)
            .register(registry).increment();
    }

    public void recordOrderBookUpdate(String exchange, String symbol) {
        Counter.builder("arb.orderbook.updates")
            .tag("exchange", exchange).tag("symbol", symbol)
            .register(registry).increment();
    }

    public void recordResync(String exchange, String symbol) {
        Counter.builder("arb.orderbook.resyncs")
            .tag("exchange", exchange).tag("symbol", symbol)
            .register(registry).increment();
    }

    public void recordWsReconnect(String exchange) {
        Counter.builder("arb.ws.reconnects")
            .tag("exchange", exchange)
            .register(registry).increment();
    }

    public void recordOpportunity(Opportunity opp) {
        String pair = opp.longExchange() + "->" + opp.shortExchange();
        Counter.builder("arb.opportunities.detected")
            .tag("symbol", opp.canonicalSymbol()).tag("pair", pair)
            .register(registry).increment();
        String key = opp.canonicalSymbol() + ":" + pair;
        lastNetSpread.computeIfAbsent(key, k -> {
            AtomicLong gauge = new AtomicLong();
            Gauge.builder("arb.opportunity.net_spread", gauge, g -> g.get() / 1_000_000.0)
                .tag("symbol", opp.canonicalSymbol()).tag("pair", pair)
                .register(registry);
            return gauge;
        }).set((long) (opp.netSpreadPct() * 1_000_000));
    }

    public void updateExchangeHealth(String exchange, boolean restAlive, boolean wsAlive) {
        String key = exchange + ":rest";
        exchangeHealth.computeIfAbsent(key, k -> {
            AtomicLong g = new AtomicLong();
            Gauge.builder("arb.exchange.health", g, AtomicLong::get)
                .tag("exchange", exchange).tag("type", "rest").register(registry);
            return g;
        }).set(restAlive ? 1 : 0);
        String wsKey = exchange + ":ws";
        exchangeHealth.computeIfAbsent(wsKey, k -> {
            AtomicLong g = new AtomicLong();
            Gauge.builder("arb.exchange.health", g, AtomicLong::get)
                .tag("exchange", exchange).tag("type", "ws").register(registry);
            return g;
        }).set(wsAlive ? 1 : 0);
    }

    public void updateClockOffset(String exchange, long offsetMs) {
        clockOffsets.computeIfAbsent(exchange, k -> {
            AtomicLong g = new AtomicLong();
            Gauge.builder("arb.clock.offset_ms", g, AtomicLong::get)
                .tag("exchange", exchange).register(registry);
            return g;
        }).set(offsetMs);
    }

    public Timer scanTimer() {
        return Timer.builder("arb.scan.duration_ms").register(registry);
    }

    public MeterRegistry getRegistry() { return registry; }
}
