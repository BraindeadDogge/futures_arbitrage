package com.arbbot.exchange.bybit;

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

public class BybitWsClient extends BaseWsClient {

    private static final Logger log = LoggerFactory.getLogger(BybitWsClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String wsBaseUrl;
    private final List<String> symbols;
    private final OrderBookManager orderBookManager;
    private final HealthMonitor healthMonitor;

    public BybitWsClient(String wsBaseUrl, List<String> symbols,
                          OrderBookManager manager, HealthMonitor healthMonitor,
                          OkHttpClient httpClient) {
        super("bybit", httpClient);
        this.wsBaseUrl = wsBaseUrl;
        this.symbols = List.copyOf(symbols);
        this.orderBookManager = manager;
        this.healthMonitor = healthMonitor;
    }

    @Override
    protected String wsUrl() { return wsBaseUrl; }

    @Override
    protected void onConnected(WebSocket ws) {
        for (String symbol : symbols) {
            send("{\"op\":\"subscribe\",\"args\":[\"orderbook.50." + symbol + "\"]}");
        }
        schedulePing();
    }

    @Override
    protected void handleMessage(WebSocket ws, String text) {
        try {
            if (text.contains("\"op\":\"pong\"")) return;
            JsonNode root = mapper.readTree(text);
            String topic = root.path("topic").asText();
            if (!topic.startsWith("orderbook")) return;

            String type = root.path("type").asText();
            JsonNode data = root.path("data");
            String symbol = data.path("s").asText();
            long seq = data.path("seq").asLong();
            OrderBook book = orderBookManager.getOrCreateBook("bybit", symbol);

            if ("snapshot".equals(type)) {
                Map<Double, Double> bids = parseLevelsToMap(data.path("b"));
                Map<Double, Double> asks = parseLevelsToMap(data.path("a"));
                book.applySnapshot(bids, asks, seq);
                log.info("[bybit] Snapshot applied for {}", symbol);
            } else if ("delta".equals(type)) {
                List<OrderBook.PriceLevel> bids = parseLevels(data.path("b"));
                List<OrderBook.PriceLevel> asks = parseLevels(data.path("a"));
                if (!book.applyDelta(bids, asks, seq)) {
                    log.warn("[bybit] Sequence gap for {} at seq={}, re-subscribing", symbol, seq);
                    send("{\"op\":\"unsubscribe\",\"args\":[\"orderbook.50." + symbol + "\"]}");
                    send("{\"op\":\"subscribe\",\"args\":[\"orderbook.50." + symbol + "\"]}");
                }
            }
            healthMonitor.recordWsTick("bybit");
        } catch (Exception e) {
            log.error("[bybit] Message parse error: {}", e.getMessage());
        }
    }

    private void schedulePing() {
        Thread.ofVirtual().name("bybit-ping").start(() -> {
            try {
                while (isConnected()) {
                    Thread.sleep(20_000);
                    send("{\"op\":\"ping\"}");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

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
            map.put(Double.parseDouble(entry.get(0).asText()), Double.parseDouble(entry.get(1).asText()));
        }
        return map;
    }
}
