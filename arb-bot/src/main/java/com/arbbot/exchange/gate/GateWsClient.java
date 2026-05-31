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

public class GateWsClient extends BaseWsClient {

    private static final Logger log = LoggerFactory.getLogger(GateWsClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String wsBaseUrl;
    private final Map<String, String> canonicalToGate; // canonical -> gate symbol e.g. BTC_USDT
    private final List<String> symbols;
    private final OrderBookManager orderBookManager;
    private final HealthMonitor healthMonitor;
    private final Map<String, Long> snapshotSeq = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> pendingDeltas = new ConcurrentHashMap<>();

    public GateWsClient(String wsBaseUrl, Map<String, String> canonicalToGate,
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
        pendingDeltas.clear();
        snapshotSeq.clear();
    }

    @Override
    protected void onConnected(WebSocket ws) {
        for (String sym : symbols) {
            pendingDeltas.put(sym, new ArrayList<>());
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
            // "subscribe" ack — skip
            if ("subscribe".equals(event)) return;

            JsonNode result = root.path("result");
            String sym = result.path("contract").asText();
            if (sym.isEmpty()) return;

            OrderBook book = orderBookManager.getOrCreateBook("gate", sym);

            if ("all".equals(event)) {
                long id = result.path("id").asLong();
                Map<Double, Double> bids = parseLevelsToMap(result.path("bids"));
                Map<Double, Double> asks = parseLevelsToMap(result.path("asks"));
                book.applySnapshot(bids, asks, id);
                snapshotSeq.put(sym, id);

                List<JsonNode> buffered = pendingDeltas.getOrDefault(sym, List.of());
                for (JsonNode delta : buffered) {
                    long dU = delta.path("U").asLong();
                    if (dU <= id) continue;
                    book.applyDelta(parseLevels(delta.path("bids")), parseLevels(delta.path("asks")), -1L);
                }
                pendingDeltas.remove(sym);
                recordDataReceived();
                log.info("[gate] Snapshot applied for {}, id={}", sym, id);

            } else if ("update".equals(event)) {
                if (!book.isInitialized()) {
                    pendingDeltas.computeIfAbsent(sym, k -> new ArrayList<>()).add(result);
                    return;
                }
                Long snapSeq = snapshotSeq.get(sym);
                long u = result.path("u").asLong();
                if (snapSeq != null && u <= snapSeq) return;
                book.applyDelta(parseLevels(result.path("bids")), parseLevels(result.path("asks")), -1L);
                recordDataReceived();
            }
            healthMonitor.recordWsTick("gate");
        } catch (Exception e) {
            log.error("[gate] Message parse error: {}", e.getMessage());
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

    private Map<Double, Double> parseLevelsToMap(JsonNode array) {
        Map<Double, Double> map = new HashMap<>();
        for (JsonNode entry : array) {
            map.put(Double.parseDouble(entry.path("p").asText()), entry.path("s").asDouble());
        }
        return map;
    }
}
