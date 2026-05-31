package com.arbbot.exchange.htx;

import com.arbbot.exchange.BaseWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBook;
import com.arbbot.market.OrderBookManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

class HtxWsClientShard extends BaseWsClient {

    private static final Logger log = LoggerFactory.getLogger(HtxWsClientShard.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String wsBaseUrl;
    private final List<String> symbols;
    private final OrderBookManager orderBookManager;
    private final HealthMonitor healthMonitor;
    private final AtomicLong msgId = new AtomicLong(1);
    private final Map<String, Long> snapshotSeq = new ConcurrentHashMap<>();
    private final Map<String, Long> lastVersion = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> pendingDeltas = new ConcurrentHashMap<>();

    HtxWsClientShard(String wsBaseUrl, List<String> symbols,
                     OrderBookManager manager, HealthMonitor healthMonitor,
                     OkHttpClient httpClient) {
        super("htx", httpClient);
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
        lastVersion.clear();
    }

    @Override
    protected void onConnected(WebSocket ws) {
        for (String sym : symbols) {
            pendingDeltas.put(sym, new ArrayList<>());
            String id = String.valueOf(msgId.getAndIncrement());
            send("{\"sub\":\"market." + sym + ".depth.size_20.high_freq\","
                + "\"id\":\"" + id + "\",\"data_type\":\"incremental\"}");
        }
        // No client-initiated ping — server pings us every 5s via compressed frames
    }

    // All HTX market data frames are GZIP-compressed binary — override the binary hook
    @Override
    protected void handleBinaryMessage(WebSocket ws, ByteString bytes) {
        try {
            String text = decompress(bytes.toByteArray());
            handleParsedMessage(ws, text);
        } catch (Exception e) {
            log.error("[htx] Binary frame error: {}", e.getMessage());
        }
    }

    // HTX does not send plain text frames for market data — this is a no-op guard
    @Override
    protected void handleMessage(WebSocket ws, String text) {
        // Server may occasionally send plain-text control messages on reconnect; ignore them
    }

    private void handleParsedMessage(WebSocket ws, String text) {
        try {
            JsonNode root = mapper.readTree(text);

            // Server-initiated ping: {"ping": N} — must reply {"pong": N}
            if (root.has("ping")) {
                long pingTs = root.path("ping").asLong();
                send("{\"pong\":" + pingTs + "}");
                return;
            }

            // Subscription confirmation: {"id":"1","subbed":"market.BTC-USDT...","status":"ok"}
            if (root.has("subbed")) return;
            if (root.has("status") && !root.has("ch")) return;

            String ch = root.path("ch").asText();
            if (ch.isEmpty()) return;
            // ch = "market.BTC-USDT.depth.size_20.high_freq"
            String[] parts = ch.split("\\.");
            if (parts.length < 2) return;
            String sym = parts[1]; // e.g. "BTC-USDT"

            JsonNode tick = root.path("tick");
            String event = tick.path("event").asText();
            long version = tick.path("version").asLong();
            OrderBook book = orderBookManager.getOrCreateBook("htx", sym);

            if ("snapshot".equals(event)) {
                Map<Double, Double> bids = parseLevelsToMap(tick.path("bids"));
                Map<Double, Double> asks = parseLevelsToMap(tick.path("asks"));
                book.applySnapshot(bids, asks, version);
                snapshotSeq.put(sym, version);
                lastVersion.put(sym, version);

                List<JsonNode> buffered = pendingDeltas.getOrDefault(sym, List.of());
                for (JsonNode delta : buffered) {
                    long dVer = delta.path("version").asLong();
                    if (dVer <= version) continue;
                    book.applyDelta(parseLevels(delta.path("bids")), parseLevels(delta.path("asks")), -1L);
                    lastVersion.put(sym, dVer);
                    version = dVer;
                }
                pendingDeltas.remove(sym);
                recordDataReceived();
                log.info("[htx] Snapshot applied for {}, version={}", sym, version);

            } else if ("update".equals(event)) {
                if (!book.isInitialized()) {
                    pendingDeltas.computeIfAbsent(sym, k -> new ArrayList<>()).add(tick);
                    return;
                }
                Long prev = lastVersion.get(sym);
                if (prev != null && version != prev + 1) {
                    // Sequence gap — book is corrupt, reconnect for fresh snapshot
                    log.warn("[htx] Sequence gap for {}: expected {} got {} — reconnecting",
                        sym, prev + 1, version);
                    forceReconnect();
                    return;
                }
                book.applyDelta(parseLevels(tick.path("bids")), parseLevels(tick.path("asks")), -1L);
                lastVersion.put(sym, version);
                recordDataReceived();
            }
            healthMonitor.recordWsTick("htx");
        } catch (Exception e) {
            log.error("[htx] Message parse error: {}", e.getMessage());
        }
    }

    private static String decompress(byte[] compressed) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            gzip.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    // HTX level format: [97000.1, 50] — numeric price and qty
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
