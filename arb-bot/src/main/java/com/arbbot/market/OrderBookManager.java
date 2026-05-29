package com.arbbot.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class OrderBookManager {

    private static final Logger log = LoggerFactory.getLogger(OrderBookManager.class);
    private static final long PRICE_FREEZE_THRESHOLD_MS = 60_000;

    private final long wsStaleThresholdMs;
    // key: "exchange:exchangeSymbol"
    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();
    // tracks which books are currently in a frozen state to log only on state transitions
    private final Map<String, Boolean> priceFreezeState = new ConcurrentHashMap<>();

    public OrderBookManager(long wsStaleThresholdMs) {
        this.wsStaleThresholdMs = wsStaleThresholdMs;
    }

    public OrderBook getOrCreateBook(String exchange, String exchangeSymbol) {
        return books.computeIfAbsent(key(exchange, exchangeSymbol),
            k -> new OrderBook(exchange, exchangeSymbol));
    }

    public Optional<Tick> getTick(String exchange, String exchangeSymbol, String canonical, double orderSizeUsdt) {
        OrderBook book = books.get(key(exchange, exchangeSymbol));
        if (book == null) return Optional.of(Tick.unreliable(canonical, exchange));
        boolean reliable = book.isInitialized() && !book.isStale(wsStaleThresholdMs);
        if (!reliable) {
            return Optional.of(Tick.unreliable(canonical, exchange));
        }
        String bookKey = exchange + "/" + exchangeSymbol;
        if (book.isBestBidFrozen(PRICE_FREEZE_THRESHOLD_MS)) {
            if (!Boolean.TRUE.equals(priceFreezeState.put(bookKey, true))) {
                log.warn("Price freeze: {}/{} best bid unchanged >{}s — excluding from scanner",
                    exchange, exchangeSymbol, PRICE_FREEZE_THRESHOLD_MS / 1000);
            }
            return Optional.of(Tick.unreliable(canonical, exchange));
        }
        if (Boolean.TRUE.equals(priceFreezeState.put(bookKey, false))) {
            log.info("Price freeze resolved: {}/{} best bid is moving again", exchange, exchangeSymbol);
        }
        double bid = book.bestBid().orElse(0);
        double ask = book.bestAsk().orElse(0);
        double effectiveBuy = book.effectiveBuyPrice(orderSizeUsdt).orElse(0);
        double effectiveSell = book.effectiveSellPrice(orderSizeUsdt).orElse(0);
        double askDepth = book.askDepthUsdt(10);
        double bidDepth = book.bidDepthUsdt(10);
        boolean depthReliable = effectiveBuy > 0 && effectiveSell > 0;
        return Optional.of(new Tick(canonical, exchange, bid, ask,
            effectiveBuy, effectiveSell, askDepth, bidDepth, Instant.now(), depthReliable));
    }

    public List<Tick> getAllTicks(String canonical, Map<String, String> exchangeSymbols, double orderSizeUsdt) {
        List<Tick> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : exchangeSymbols.entrySet()) {
            getTick(entry.getKey(), entry.getValue(), canonical, orderSizeUsdt)
                .ifPresent(result::add);
        }
        return result;
    }

    private static String key(String exchange, String symbol) {
        return exchange + ":" + symbol;
    }
}
