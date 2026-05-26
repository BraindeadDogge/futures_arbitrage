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
import java.util.concurrent.atomic.AtomicLong;

public class KuCoinWsClient extends BaseWsClient {

    private static final Logger log = LoggerFactory.getLogger(KuCoinWsClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String restBaseUrl;
    private final List<String> symbols; // KuCoin format: BTCUSDTM
    private final Map<String, String> canonicalToKucoin; // canonical -> kucoin symbol
    private final OrderBookManager orderBookManager;
    private final HealthMonitor healthMonitor;
    private volatile String wsEndpoint = "";
    private volatile long pingIntervalMs = 18_000;
    private final AtomicLong msgId = new AtomicLong(1);

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
        // Fetch WS token first, then return connect URL
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
            return "ws://invalid"; // will fail and trigger reconnect
        }
    }

    @Override
    protected void onConnected(WebSocket ws) {
        // Subscribe to all symbols
        for (String sym : symbols) {
            String id = String.valueOf(msgId.getAndIncrement());
            send("{\"id\":\"" + id + "\",\"type\":\"subscribe\",\"topic\":\"/contractMarket/level2:" + sym
                + "\",\"privateChannel\":false,\"response\":true}");
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

            // KuCoin sends incremental changes as "change" field: "price,side,qty|..."
            String changeStr = data.path("change").asText("");
            if (!changeStr.isEmpty()) {
                List<OrderBook.PriceLevel> bids = new ArrayList<>();
                List<OrderBook.PriceLevel> asks = new ArrayList<>();
                for (String entry : changeStr.split("\\|")) {
                    String[] parts = entry.split(",");
                    if (parts.length < 3) continue;
                    double price = Double.parseDouble(parts[0]);
                    String side = parts[1];
                    double qty = Double.parseDouble(parts[2]);
                    if ("buy".equals(side)) bids.add(new OrderBook.PriceLevel(price, qty));
                    else asks.add(new OrderBook.PriceLevel(price, qty));
                }
                OrderBook book = orderBookManager.getOrCreateBook("kucoin", kucoinSymbol);
                if (!book.isInitialized()) {
                    // Book not initialized - need snapshot first; trigger re-fetch
                    log.warn("[kucoin] Book not initialized for {}, ignoring delta", kucoinSymbol);
                    return;
                }
                if (!book.applyDelta(bids, asks, seq)) {
                    log.warn("[kucoin] Sequence gap for {}, re-subscribing", kucoinSymbol);
                    String id = String.valueOf(msgId.getAndIncrement());
                    send("{\"id\":\"" + id + "\",\"type\":\"unsubscribe\",\"topic\":\"/contractMarket/level2:"
                        + kucoinSymbol + "\"}");
                    String id2 = String.valueOf(msgId.getAndIncrement());
                    send("{\"id\":\"" + id2 + "\",\"type\":\"subscribe\",\"topic\":\"/contractMarket/level2:"
                        + kucoinSymbol + "\",\"privateChannel\":false,\"response\":true}");
                }
                healthMonitor.recordWsTick("kucoin");
            }

            // KuCoin may also send snapshot-style messages with bids/asks arrays
            JsonNode bidsNode = data.path("bids");
            JsonNode asksNode = data.path("asks");
            if (!bidsNode.isMissingNode() && !asksNode.isMissingNode()) {
                Map<Double, Double> bids = new HashMap<>();
                Map<Double, Double> asks = new HashMap<>();
                for (JsonNode b : bidsNode) bids.put(b.get(0).asDouble(), b.get(1).asDouble());
                for (JsonNode a : asksNode) asks.put(a.get(0).asDouble(), a.get(1).asDouble());
                orderBookManager.getOrCreateBook("kucoin", kucoinSymbol).applySnapshot(bids, asks, seq);
                healthMonitor.recordWsTick("kucoin");
            }
        } catch (Exception e) {
            log.error("[kucoin] Message parse error: {}", e.getMessage());
        }
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
