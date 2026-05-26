package com.arbbot.exchange.binance;

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

public class BinanceWsClient extends BaseWsClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceWsClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String wsBaseUrl;
    private final String restBaseUrl;
    private final List<String> symbols;
    private final OrderBookManager orderBookManager;
    private final HealthMonitor healthMonitor;
    private final Map<String, List<JsonNode>> pendingDeltas = new ConcurrentHashMap<>();

    public BinanceWsClient(String wsBaseUrl, String restBaseUrl, List<String> symbols,
                            OrderBookManager manager, HealthMonitor healthMonitor,
                            OkHttpClient httpClient) {
        super("binance", httpClient);
        this.wsBaseUrl = wsBaseUrl;
        this.restBaseUrl = restBaseUrl;
        this.symbols = List.copyOf(symbols);
        this.orderBookManager = manager;
        this.healthMonitor = healthMonitor;
    }

    @Override
    protected String wsUrl() {
        String streams = symbols.stream()
            .map(s -> s.toLowerCase() + "@depth@100ms")
            .reduce((a, b) -> a + "/" + b).orElse("");
        return wsBaseUrl + "/stream?streams=" + streams;
    }

    @Override
    protected void onConnected(WebSocket ws) {
        symbols.forEach(symbol -> {
            pendingDeltas.put(symbol, new ArrayList<>());
            fetchSnapshot(symbol);
        });
        schedulePing();
    }

    @Override
    protected void handleMessage(WebSocket ws, String text) {
        try {
            JsonNode root = mapper.readTree(text);
            JsonNode data = root.path("data");
            if (data.isMissingNode()) data = root;

            if (!"depthUpdate".equals(data.path("e").asText())) return;

            String symbol = data.path("s").asText();
            long U = data.path("U").asLong();
            long u = data.path("u").asLong();
            List<OrderBook.PriceLevel> bids = parseLevels(data.path("b"));
            List<OrderBook.PriceLevel> asks = parseLevels(data.path("a"));
            OrderBook book = orderBookManager.getOrCreateBook("binance", symbol);

            if (!book.isInitialized()) {
                pendingDeltas.computeIfAbsent(symbol, k -> new ArrayList<>()).add(data);
                return;
            }
            long lastSeq = book.getLastSeqNum();
            if (u < lastSeq + 1) return;
            if (U > lastSeq + 1) {
                log.warn("[binance] Sequence gap for {}: U={} > lastSeq+1={}", symbol, U, lastSeq + 1);
                pendingDeltas.put(symbol, new ArrayList<>());
                fetchSnapshot(symbol);
                return;
            }
            book.applyDelta(bids, asks, -1L);
            healthMonitor.recordWsTick("binance");
        } catch (Exception e) {
            log.error("[binance] Message parse error: {}", e.getMessage());
        }
    }

    void fetchSnapshot(String symbol) {
        Thread.ofVirtual().name("binance-snapshot-" + symbol).start(() -> {
            try {
                okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(restBaseUrl + "/fapi/v1/depth?symbol=" + symbol + "&limit=200")
                    .get().build();
                try (okhttp3.Response resp = httpClient.newCall(req).execute()) {
                    JsonNode root = mapper.readTree(resp.body().string());
                    long lastUpdateId = root.path("lastUpdateId").asLong();
                    Map<Double, Double> bids = parseLevelsToMap(root.path("bids"));
                    Map<Double, Double> asks = parseLevelsToMap(root.path("asks"));
                    OrderBook book = orderBookManager.getOrCreateBook("binance", symbol);
                    book.applySnapshot(bids, asks, lastUpdateId);

                    List<JsonNode> buffered = pendingDeltas.getOrDefault(symbol, List.of());
                    for (JsonNode delta : buffered) {
                        long dU = delta.path("U").asLong();
                        long du = delta.path("u").asLong();
                        if (du < lastUpdateId + 1) continue;
                        if (dU > lastUpdateId + 1) {
                            log.warn("[binance] Gap in buffered deltas for {}: dU={} > lastUpdateId+1={}", symbol, dU, lastUpdateId + 1);
                            break;
                        }
                        book.applyDelta(parseLevels(delta.path("b")), parseLevels(delta.path("a")), -1L);
                    }
                    pendingDeltas.remove(symbol);
                    log.info("[binance] Snapshot applied for {}, lastUpdateId={}", symbol, lastUpdateId);
                }
            } catch (Exception e) {
                log.error("[binance] Snapshot fetch failed for {}: {}", symbol, e.getMessage());
            }
        });
    }

    /** Visible-for-testing hook */
    void fetchSnapshotForTest(String symbol) {
        fetchSnapshot(symbol);
    }

    private void schedulePing() {
        Thread.ofVirtual().name("binance-ping").start(() -> {
            try {
                while (isConnected()) {
                    Thread.sleep(180_000);
                    send("{\"method\":\"ping\"}");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private List<OrderBook.PriceLevel> parseLevels(JsonNode array) {
        List<OrderBook.PriceLevel> levels = new ArrayList<>();
        for (JsonNode entry : array) {
            double price = entry.get(0).asDouble();
            double qty = entry.get(1).asDouble();
            levels.add(new OrderBook.PriceLevel(price, qty));
        }
        return levels;
    }

    private Map<Double, Double> parseLevelsToMap(JsonNode array) {
        Map<Double, Double> map = new HashMap<>();
        for (JsonNode entry : array) {
            map.put(entry.get(0).asDouble(), entry.get(1).asDouble());
        }
        return map;
    }
}
