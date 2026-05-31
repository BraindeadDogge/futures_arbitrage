package com.arbbot.exchange.kucoin;

import com.arbbot.exchange.BaseWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBook;
import com.arbbot.market.OrderBookManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class KuCoinWsClient extends BaseWsClient {

    private static final Logger log = LoggerFactory.getLogger(KuCoinWsClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String restBaseUrl;
    private final List<String> symbols;
    private final Map<String, String> canonicalToKucoin;
    private final OrderBookManager orderBookManager;
    private final HealthMonitor healthMonitor;
    private volatile String wsEndpoint = "";
    private volatile long pingIntervalMs = 18_000;
    private final AtomicLong msgId = new AtomicLong(1);
    private final Map<String, CopyOnWriteArrayList<JsonNode>> pendingDeltas = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> snapshotInProgress = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAppliedSeq = new ConcurrentHashMap<>();

    public KuCoinWsClient(String restBaseUrl, Map<String, String> canonicalToKucoin,
                          OrderBookManager manager, HealthMonitor healthMonitor,
                          OkHttpClient httpClient) {
        super("kucoin", httpClient);
        this.restBaseUrl = restBaseUrl;
        this.canonicalToKucoin = Map.copyOf(canonicalToKucoin);
        this.symbols = List.copyOf(canonicalToKucoin.values());
        this.orderBookManager = manager;
        this.healthMonitor = healthMonitor;
    }

    @Override
    protected String wsUrl() {
        try {
            Request req = new Request.Builder()
                .url(restBaseUrl + "/api/v1/bullet-public")
                .post(RequestBody.create(new byte[0])).build();
            try (Response resp = httpClient.newCall(req).execute()) {
                JsonNode root = mapper.readTree(resp.body().string());
                JsonNode server = root.path("data").path("instanceServers").get(0);
                String token = root.path("data").path("token").asText();
                wsEndpoint = server.path("endpoint").asText();
                pingIntervalMs = server.path("pingInterval").asLong(18_000);
                String connectId = java.util.UUID.randomUUID().toString().replace("-", "");
                return wsEndpoint + "?token=" + token + "&connectId=" + connectId;
            }
        } catch (Exception e) {
            log.error("[kucoin] Failed to get WS token: {}", e.getMessage());
            return "ws://invalid";
        }
    }

    @Override
    protected void onBeforeReconnect() {
        pendingDeltas.clear();
        snapshotInProgress.clear();
        lastAppliedSeq.clear();
    }

    @Override
    protected void onConnected(WebSocket ws) {
        for (String sym : symbols) {
            pendingDeltas.put(sym, new CopyOnWriteArrayList<>());
            snapshotInProgress.computeIfAbsent(sym, k -> new AtomicBoolean(false)).set(true);
            String id = String.valueOf(msgId.getAndIncrement());
            send("{\"id\":\"" + id + "\",\"type\":\"subscribe\",\"topic\":\"/contractMarket/level2:" + sym
                + "\",\"privateChannel\":false,\"response\":true}");
            fetchSnapshot(sym);
        }
        schedulePing();
    }

    @Override
    protected void handleMessage(WebSocket ws, String text) {
        try {
            if ("pong".equals(text)) return;
            JsonNode root = mapper.readTree(text);
            String type = root.path("type").asText();
            if ("pong".equals(type) || "ack".equals(type) || "welcome".equals(type)) return;

            String topic = root.path("topic").asText();
            if (!topic.startsWith("/contractMarket/level2:")) return;

            String kucoinSymbol = topic.substring("/contractMarket/level2:".length());
            JsonNode data = root.path("data");
            long seq = data.path("sequence").asLong();

            String changeStr = data.path("change").asText("");
            if (changeStr.isEmpty()) return;

            OrderBook book = orderBookManager.getOrCreateBook("kucoin", kucoinSymbol);
            AtomicBoolean inProgress = snapshotInProgress.computeIfAbsent(kucoinSymbol, k -> new AtomicBoolean(false));

            if (!book.isInitialized() || inProgress.get()) {
                pendingDeltas.computeIfAbsent(kucoinSymbol, k -> new CopyOnWriteArrayList<>()).add(data);
                return;
            }
            // KuCoin futures depth stream sends absolute quantities per price level,
            // so skipping a sequence gap only causes transient staleness — no re-fetch needed.
            if (seq <= lastAppliedSeq.getOrDefault(kucoinSymbol, 0L)) return;
            List<OrderBook.PriceLevel> bids = new ArrayList<>(), asks = new ArrayList<>();
            parseChange(changeStr, bids, asks);
            book.applyDelta(bids, asks, -1L);
            lastAppliedSeq.put(kucoinSymbol, seq);
            recordDataReceived();
            healthMonitor.recordWsTick("kucoin");
        } catch (Exception e) {
            log.error("[kucoin] Message parse error: {}", e.getMessage());
        }
    }

    private void fetchSnapshot(String kucoinSymbol) {
        Thread.ofVirtual().name("kucoin-snapshot-" + kucoinSymbol).start(() -> {
            try {
                Request req = new Request.Builder()
                    .url(restBaseUrl + "/api/v1/level2/snapshot?symbol=" + kucoinSymbol)
                    .get().build();
                try (Response resp = httpClient.newCall(req).execute()) {
                    String body = resp.body().string();
                    if (!resp.isSuccessful()) {
                        log.error("[kucoin] Snapshot HTTP {} for {}: {}", resp.code(), kucoinSymbol, body);
                        snapshotInProgress.getOrDefault(kucoinSymbol, new AtomicBoolean()).set(false);
                        return;
                    }
                    JsonNode root = mapper.readTree(body);
                    JsonNode data = root.path("data");
                    if (data.isMissingNode()) {
                        log.error("[kucoin] No data field in snapshot for {}, body: {}", kucoinSymbol, body);
                        snapshotInProgress.getOrDefault(kucoinSymbol, new AtomicBoolean()).set(false);
                        return;
                    }
                    long seq = data.path("sequence").asLong();
                    if (seq == 0) {
                        log.error("[kucoin] sequence=0 for {}, body: {}", kucoinSymbol, body);
                        snapshotInProgress.getOrDefault(kucoinSymbol, new AtomicBoolean()).set(false);
                        return;
                    }
                    Map<Double, Double> bids = parseLevelsToMap(data.path("bids"));
                    Map<Double, Double> asks = parseLevelsToMap(data.path("asks"));
                    OrderBook book = orderBookManager.getOrCreateBook("kucoin", kucoinSymbol);
                    book.applySnapshot(bids, asks, seq);
                    lastAppliedSeq.put(kucoinSymbol, seq);

                    // Swap buffer atomically; CopyOnWriteArrayList makes concurrent adds safe.
                    List<JsonNode> buffered = pendingDeltas.put(kucoinSymbol, new CopyOnWriteArrayList<>());
                    if (buffered != null) {
                        for (JsonNode delta : buffered) {
                            long dSeq = delta.path("sequence").asLong();
                            if (dSeq <= seq) continue;
                            List<OrderBook.PriceLevel> dbids = new ArrayList<>(), dasks = new ArrayList<>();
                            parseChange(delta.path("change").asText(""), dbids, dasks);
                            book.applyDelta(dbids, dasks, -1L);
                            lastAppliedSeq.put(kucoinSymbol, dSeq);
                            seq = dSeq;
                        }
                    }
                    snapshotInProgress.getOrDefault(kucoinSymbol, new AtomicBoolean()).set(false);
                    recordDataReceived();
                    log.info("[kucoin] Snapshot applied for {}, seq={}", kucoinSymbol, seq);
                }
            } catch (Exception e) {
                log.error("[kucoin] Snapshot fetch failed for {}: {}", kucoinSymbol, e.getMessage());
                snapshotInProgress.getOrDefault(kucoinSymbol, new AtomicBoolean()).set(false);
            }
        });
    }

    private static void parseChange(String changeStr,
                                    List<OrderBook.PriceLevel> bids, List<OrderBook.PriceLevel> asks) {
        if (changeStr.isEmpty()) return;
        for (String entry : changeStr.split("\\|")) {
            String[] parts = entry.split(",");
            if (parts.length < 3) continue;
            double price = Double.parseDouble(parts[0]);
            String side = parts[1];
            double qty = Double.parseDouble(parts[2]);
            if ("buy".equals(side)) bids.add(new OrderBook.PriceLevel(price, qty));
            else asks.add(new OrderBook.PriceLevel(price, qty));
        }
    }

    private Map<Double, Double> parseLevelsToMap(JsonNode array) {
        Map<Double, Double> map = new HashMap<>();
        for (JsonNode entry : array) {
            map.put(Double.parseDouble(entry.get(0).asText()), Double.parseDouble(entry.get(1).asText()));
        }
        return map;
    }

    private void schedulePing() {
        long interval = pingIntervalMs;
        Thread.ofVirtual().name("kucoin-ping").start(() -> {
            try {
                while (isConnected()) {
                    Thread.sleep(interval);
                    String id = String.valueOf(msgId.getAndIncrement());
                    send("{\"id\":\"" + id + "\",\"type\":\"ping\"}");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
