package com.arbbot.exchange.okx;

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

public class OkxWsClient extends BaseWsClient {

    private static final Logger log = LoggerFactory.getLogger(OkxWsClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String wsBaseUrl;
    private final Map<String, String> canonicalToOkx; // canonical -> instId e.g. BTC-USDT-SWAP
    private final OrderBookManager orderBookManager;
    private final HealthMonitor healthMonitor;
    private final Map<String, Long> snapshotSeq = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> pendingDeltas = new ConcurrentHashMap<>();

    public OkxWsClient(String wsBaseUrl, Map<String, String> canonicalToOkx,
                        OrderBookManager manager, HealthMonitor healthMonitor,
                        OkHttpClient httpClient) {
        super("okx", httpClient);
        this.wsBaseUrl = wsBaseUrl;
        this.canonicalToOkx = Map.copyOf(canonicalToOkx);
        this.orderBookManager = manager;
        this.healthMonitor = healthMonitor;
    }

    @Override
    protected String wsUrl() { return wsBaseUrl; }

    @Override
    protected void onConnected(WebSocket ws) {
        StringBuilder args = new StringBuilder();
        for (String instId : canonicalToOkx.values()) {
            pendingDeltas.put(instId, new ArrayList<>());
            if (args.length() > 0) args.append(",");
            args.append("{\"channel\":\"books\",\"instId\":\"").append(instId).append("\"}");
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
            if (action.isEmpty()) return; // not a data message

            JsonNode arg = root.path("arg");
            String instId = arg.path("instId").asText();
            JsonNode dataArr = root.path("data");
            if (!dataArr.isArray() || dataArr.isEmpty()) return;
            JsonNode data = dataArr.get(0);

            long seqId = data.path("seqId").asLong();
            OrderBook book = orderBookManager.getOrCreateBook("okx", instId);

            if ("snapshot".equals(action)) {
                Map<Double, Double> bids = parseLevelsToMap(data.path("bids"));
                Map<Double, Double> asks = parseLevelsToMap(data.path("asks"));
                book.applySnapshot(bids, asks, seqId);
                snapshotSeq.put(instId, seqId);
                List<JsonNode> buffered = pendingDeltas.getOrDefault(instId, List.of());
                for (JsonNode delta : buffered) {
                    long dSeq = delta.path("seqId").asLong();
                    if (dSeq <= seqId) continue;
                    book.applyDelta(parseLevels(delta.path("bids")), parseLevels(delta.path("asks")), -1L);
                }
                pendingDeltas.remove(instId);
                log.info("[okx] Snapshot applied for {}, seqId={}", instId, seqId);
            } else if ("update".equals(action)) {
                if (!book.isInitialized()) {
                    pendingDeltas.computeIfAbsent(instId, k -> new ArrayList<>()).add(data);
                    return;
                }
                Long snapSeq = snapshotSeq.get(instId);
                if (snapSeq != null && seqId <= snapSeq) return;
                book.applyDelta(parseLevels(data.path("bids")), parseLevels(data.path("asks")), -1L);
            }
            healthMonitor.recordWsTick("okx");
        } catch (Exception e) {
            log.error("[okx] Message parse error: {}", e.getMessage());
        }
    }

    private void schedulePing() {
        Thread.ofVirtual().name("okx-ping").start(() -> {
            try {
                while (isConnected()) {
                    Thread.sleep(25_000);
                    send("ping"); // OKX uses plain string ping/pong
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private List<OrderBook.PriceLevel> parseLevels(JsonNode array) {
        List<OrderBook.PriceLevel> levels = new ArrayList<>();
        for (JsonNode entry : array) {
            // OKX format: [price, qty, deprecated, numOrders]
            double price = Double.parseDouble(entry.get(0).asText());
            double qty = Double.parseDouble(entry.get(1).asText());
            levels.add(new OrderBook.PriceLevel(price, qty));
        }
        return levels;
    }

    private Map<Double, Double> parseLevelsToMap(JsonNode array) {
        Map<Double, Double> map = new HashMap<>();
        for (JsonNode entry : array) {
            map.put(Double.parseDouble(entry.get(0).asText()), Double.parseDouble(entry.get(1).asText()));
        }
        return map;
    }
}
