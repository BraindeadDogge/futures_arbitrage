package com.arbbot.exchange.gate;

import com.arbbot.exchange.BaseWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBook;
import com.arbbot.market.OrderBookManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

class GateWsClientShard extends BaseWsClient {

    private static final Logger log = LoggerFactory.getLogger(GateWsClientShard.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String wsBaseUrl;
    private final Map<String, String> canonicalToGate; // canonical -> gate symbol e.g. BTC_USDT
    private final List<String> symbols;
    private final OrderBookManager orderBookManager;
    private final HealthMonitor healthMonitor;
    private final Map<String, Map<Double, Double>> accumBids = new ConcurrentHashMap<>();
    private final Map<String, Map<Double, Double>> accumAsks = new ConcurrentHashMap<>();

    GateWsClientShard(String wsBaseUrl, Map<String, String> canonicalToGate,
                      OrderBookManager manager, HealthMonitor healthMonitor,
                      OkHttpClient httpClient) {
        super("gate", httpClient);
        this.wsBaseUrl = wsBaseUrl;
        this.canonicalToGate = Map.copyOf(canonicalToGate);
        this.symbols = List.copyOf(canonicalToGate.values());
        this.orderBookManager = manager;
        this.healthMonitor = healthMonitor;
    }

    @Override
    protected String wsUrl() { return wsBaseUrl; }

    @Override
    protected void onBeforeReconnect() {
        accumBids.clear();
        accumAsks.clear();
    }

    @Override
    protected void onConnected(WebSocket ws) {
        for (String sym : symbols) {
            long t = System.currentTimeMillis() / 1000;
            send("{\"time\":" + t + ",\"channel\":\"futures.order_book_update\","
                + "\"event\":\"subscribe\",\"payload\":[\"" + sym + "\",\"100ms\",\"20\"]}");
        }
        schedulePing();
    }

    @Override
    protected void handleMessage(WebSocket ws, String text) {
        try {
            JsonNode root = mapper.readTree(text);
            String channel = root.path("channel").asText();
            if ("futures.pong".equals(channel)) return;
            if (!"futures.order_book_update".equals(channel)) return;

            String event = root.path("event").asText();
            if ("subscribe".equals(event)) return;
            if (!"update".equals(event)) return;

            JsonNode result = root.path("result");
            String sym = result.path("s").asText();  // field is "s", not "contract"
            if (sym.isEmpty()) return;

            OrderBook book = orderBookManager.getOrCreateBook("gate", sym);
            List<OrderBook.PriceLevel> bidLevels = parseLevels(result.path("b"));  // "b", not "bids"
            List<OrderBook.PriceLevel> askLevels = parseLevels(result.path("a"));  // "a", not "asks"

            if (!book.isInitialized()) {
                // futures.order_book_update has no snapshot event — accumulate deltas until both sides populated
                Map<Double, Double> bids = accumBids.computeIfAbsent(sym, k -> new HashMap<>());
                Map<Double, Double> asks = accumAsks.computeIfAbsent(sym, k -> new HashMap<>());
                applyToMap(bids, bidLevels);
                applyToMap(asks, askLevels);
                if (!bids.isEmpty() && !asks.isEmpty()) {
                    book.applySnapshot(bids, asks, result.path("u").asLong());
                    accumBids.remove(sym);
                    accumAsks.remove(sym);
                    recordDataReceived();
                    log.info("[gate] Book initialized from deltas for {}", sym);
                }
            } else {
                book.applyDelta(bidLevels, askLevels, -1L);
                recordDataReceived();
            }
            healthMonitor.recordWsTick("gate");
        } catch (Exception e) {
            log.error("[gate] Message parse error: {}", e.getMessage());
        }
    }

    private void applyToMap(Map<Double, Double> map, List<OrderBook.PriceLevel> levels) {
        for (OrderBook.PriceLevel level : levels) {
            if (level.qty() <= 0.0) map.remove(level.price());
            else map.put(level.price(), level.qty());
        }
    }

    private void schedulePing() {
        Thread.ofVirtual().name("gate-ping").start(() -> {
            try {
                while (isConnected()) {
                    Thread.sleep(10_000);
                    long t = System.currentTimeMillis() / 1000;
                    send("{\"time\":" + t + ",\"channel\":\"futures.ping\"}");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // Gate.io level format: {"p": "97000.1", "s": 50}
    private List<OrderBook.PriceLevel> parseLevels(JsonNode array) {
        List<OrderBook.PriceLevel> levels = new ArrayList<>();
        for (JsonNode entry : array) {
            double price = Double.parseDouble(entry.path("p").asText());
            double qty = entry.path("s").asDouble();
            levels.add(new OrderBook.PriceLevel(price, qty));
        }
        return levels;
    }

}
