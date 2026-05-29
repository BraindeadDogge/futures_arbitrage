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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class BinanceWsClient extends BaseWsClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceWsClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String wsBaseUrl;
    private final String restBaseUrl;
    private final List<String> symbols;
    private final OrderBookManager orderBookManager;
    private final HealthMonitor healthMonitor;
    private final Map<String, CopyOnWriteArrayList<JsonNode>> pendingDeltas = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> snapshotInProgress = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAppliedU = new ConcurrentHashMap<>();

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
            pendingDeltas.put(symbol, new CopyOnWriteArrayList<>());
            snapshotInProgress.computeIfAbsent(symbol, k -> new AtomicBoolean(false)).set(true);
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
            long u = data.path("u").asLong();
            List<OrderBook.PriceLevel> bids = parseLevels(data.path("b"));
            List<OrderBook.PriceLevel> asks = parseLevels(data.path("a"));
            OrderBook book = orderBookManager.getOrCreateBook("binance", symbol);

            AtomicBoolean inProgress = snapshotInProgress.computeIfAbsent(symbol, k -> new AtomicBoolean(false));
            if (!book.isInitialized() || inProgress.get()) {
                pendingDeltas.computeIfAbsent(symbol, k -> new CopyOnWriteArrayList<>()).add(data);
                return;
            }
            // Binance FAPI depth stream sends absolute quantities per price level,
            // so skipping a sequence gap only causes transient staleness — no re-fetch needed.
            if (u <= lastAppliedU.getOrDefault(symbol, 0L)) return;
            book.applyDelta(bids, asks, -1L);
            lastAppliedU.put(symbol, u);
            healthMonitor.recordWsTick("binance");
        } catch (Exception e) {
            log.error("[binance] Message parse error: {}", e.getMessage());
        }
    }

    void fetchSnapshot(String symbol) {
        Thread.ofVirtual().name("binance-snapshot-" + symbol).start(() -> {
            try {
                okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(restBaseUrl + "/fapi/v1/depth?symbol=" + symbol + "&limit=100")
                    .get().build();
                try (okhttp3.Response resp = httpClient.newCall(req).execute()) {
                    String body = resp.body().string();
                    if (!resp.isSuccessful()) {
                        log.error("[binance] Snapshot HTTP {} for {}: {}", resp.code(), symbol, body);
                        snapshotInProgress.getOrDefault(symbol, new AtomicBoolean()).set(false);
                        return;
                    }
                    JsonNode root = mapper.readTree(body);
                    long lastUpdateId = root.path("lastUpdateId").asLong();
                    if (lastUpdateId == 0) {
                        log.error("[binance] lastUpdateId=0 for {}, body: {}", symbol, body);
                        snapshotInProgress.getOrDefault(symbol, new AtomicBoolean()).set(false);
                        return;
                    }
                    Map<Double, Double> bids = parseLevelsToMap(root.path("bids"));
                    Map<Double, Double> asks = parseLevelsToMap(root.path("asks"));
                    OrderBook book = orderBookManager.getOrCreateBook("binance", symbol);
                    book.applySnapshot(bids, asks, lastUpdateId);
                    lastAppliedU.put(symbol, lastUpdateId);

                    // Swap buffer atomically; CopyOnWriteArrayList makes concurrent adds safe.
                    List<JsonNode> buffered = pendingDeltas.put(symbol, new CopyOnWriteArrayList<>());
                    if (buffered != null) {
                        for (JsonNode delta : buffered) {
                            long du = delta.path("u").asLong();
                            if (du <= lastUpdateId) continue;
                            book.applyDelta(parseLevels(delta.path("b")), parseLevels(delta.path("a")), -1L);
                            lastAppliedU.put(symbol, du);
                            lastUpdateId = du;
                        }
                    }
                    snapshotInProgress.getOrDefault(symbol, new AtomicBoolean()).set(false);
                    log.info("[binance] Snapshot applied for {}, lastUpdateId={}", symbol, lastUpdateId);
                }
            } catch (Exception e) {
                log.error("[binance] Snapshot fetch failed for {}: {}", symbol, e.getMessage());
                snapshotInProgress.getOrDefault(symbol, new AtomicBoolean()).set(false);
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
