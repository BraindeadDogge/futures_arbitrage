package com.arbbot.exchange.bitget;

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

public class BitgetWsClient extends BaseWsClient {

    private static final Logger log = LoggerFactory.getLogger(BitgetWsClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String wsBaseUrl;
    private final List<String> symbols;
    private final OrderBookManager orderBookManager;
    private final HealthMonitor healthMonitor;
    private final Map<String, Long> snapshotSeq = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> pendingDeltas = new ConcurrentHashMap<>();

    public BitgetWsClient(String wsBaseUrl, List<String> symbols,
                          OrderBookManager manager, HealthMonitor healthMonitor,
                          OkHttpClient httpClient) {
        super("bitget", httpClient);
        this.wsBaseUrl = wsBaseUrl;
        this.symbols = List.copyOf(symbols);
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
        StringBuilder args = new StringBuilder();
        for (String sym : symbols) {
            pendingDeltas.put(sym, new ArrayList<>());
            if (args.length() > 0) args.append(",");
            args.append("{\"instType\":\"USDT-FUTURES\",\"channel\":\"books\",\"instId\":\"")
                .append(sym).append("\"}");
        }
        send("{\"op\":\"subscribe\",\"args\":[" + args + "]}");
        schedulePing();
    }

    @Override
    protected void handleMessage(WebSocket ws, String text) {
        if ("pong".equals(text)) return;
        try {
            JsonNode root = mapper.readTree(text);
            String action = root.path("action").asText();
            if (action.isEmpty()) return; // subscription ack or other control message

            JsonNode arg = root.path("arg");
            String instId = arg.path("instId").asText();
            JsonNode dataArr = root.path("data");
            if (!dataArr.isArray() || dataArr.isEmpty()) return;
            JsonNode data = dataArr.get(0);

            long seq = data.path("seq").asLong();
            OrderBook book = orderBookManager.getOrCreateBook("bitget", instId);

            if ("snapshot".equals(action)) {
                Map<Double, Double> bids = parseLevelsToMap(data.path("bids"));
                Map<Double, Double> asks = parseLevelsToMap(data.path("asks"));
                book.applySnapshot(bids, asks, seq);
                snapshotSeq.put(instId, seq);
                List<JsonNode> buffered = pendingDeltas.getOrDefault(instId, List.of());
                for (JsonNode delta : buffered) {
                    long dSeq = delta.path("seq").asLong();
                    if (dSeq <= seq) continue;
                    book.applyDelta(parseLevels(delta.path("bids")), parseLevels(delta.path("asks")), -1L);
                }
                pendingDeltas.remove(instId);
                recordDataReceived();
                log.info("[bitget] Snapshot applied for {}, seq={}", instId, seq);

            } else if ("update".equals(action)) {
                if (!book.isInitialized()) {
                    pendingDeltas.computeIfAbsent(instId, k -> new ArrayList<>()).add(data);
                    return;
                }
                Long snapSeq = snapshotSeq.get(instId);
                if (snapSeq != null && seq <= snapSeq) return;
                book.applyDelta(parseLevels(data.path("bids")), parseLevels(data.path("asks")), -1L);
                recordDataReceived();
            }
            healthMonitor.recordWsTick("bitget");
        } catch (Exception e) {
            log.error("[bitget] Message parse error: {}", e.getMessage());
        }
    }

    private void schedulePing() {
        Thread.ofVirtual().name("bitget-ping").start(() -> {
            try {
                while (isConnected()) {
                    Thread.sleep(30_000);
                    send("ping");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // Bitget level format: ["97000.1", "50"] — string price, string qty
    private List<OrderBook.PriceLevel> parseLevels(JsonNode array) {
        List<OrderBook.PriceLevel> levels = new ArrayList<>();
        for (JsonNode entry : array) {
            double price = Double.parseDouble(entry.get(0).asText());
            double qty = Double.parseDouble(entry.get(1).asText());
            levels.add(new OrderBook.PriceLevel(price, qty));
        }
        return levels;
    }

    private Map<Double, Double> parseLevelsToMap(JsonNode array) {
        Map<Double, Double> map = new HashMap<>();
        for (JsonNode entry : array) {
            map.put(Double.parseDouble(entry.get(0).asText()),
                    Double.parseDouble(entry.get(1).asText()));
        }
        return map;
    }
}
