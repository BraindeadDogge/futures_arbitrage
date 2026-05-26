package com.arbbot.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymbolRegistry {

    private static final Logger log = LoggerFactory.getLogger(SymbolRegistry.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final OkHttpClient httpClient;
    // canonical → exchange → exchangeSymbol
    private final Map<String, Map<String, String>> symbolMap = new ConcurrentHashMap<>();
    private volatile List<String> watchedSymbols = List.of();

    public SymbolRegistry() {
        this.httpClient = new OkHttpClient();
    }

    public SymbolRegistry(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public enum ExchangeFormat {
        BINANCE,
        KUCOIN,
        BYBIT,
        OKX
    }

    public void setWatchedSymbols(List<String> symbols) {
        this.watchedSymbols = List.copyOf(symbols);
    }

    public void loadExchange(String exchange, String url, ExchangeFormat format) {
        try {
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = httpClient.newCall(req).execute()) {
                String body = resp.body().string();
                JsonNode root = mapper.readTree(body);
                parseSymbols(exchange, root, format);
            }
        } catch (Exception e) {
            log.error("[{}] Failed to load symbols: {}", exchange, e.getMessage());
        }
    }

    private void parseSymbols(String exchange, JsonNode root, ExchangeFormat format) {
        switch (format) {
            case BINANCE -> {
                for (JsonNode s : root.path("symbols")) {
                    if (!"TRADING".equals(s.path("status").asText())) continue;
                    if (!"PERPETUAL".equals(s.path("contractType").asText())) continue;
                    if (!"USDT".equals(s.path("marginAsset").asText())) continue; // exclude inverse
                    String base = s.path("baseAsset").asText();
                    String exSym = s.path("symbol").asText();
                    register(base, exchange, exSym);
                }
            }
            case KUCOIN -> {
                for (JsonNode s : root.path("data")) {
                    if (s.path("isInverse").asBoolean(false)) continue;
                    String symbol = s.path("symbol").asText(); // e.g. BTCUSDTM
                    String base = symbol.endsWith("USDTM") ? symbol.substring(0, symbol.length() - 5)
                            : symbol.endsWith("USDT")  ? symbol.substring(0, symbol.length() - 4)
                            : symbol;
                    register(base, exchange, symbol);
                }
            }
            case BYBIT -> {
                for (JsonNode s : root.path("result").path("list")) {
                    if (!"LinearPerpetual".equals(s.path("contractType").asText())) continue;
                    String sym = s.path("symbol").asText(); // e.g. BTCUSDT
                    String base = sym.replace("USDT", "");
                    register(base, exchange, sym);
                }
            }
            case OKX -> {
                for (JsonNode s : root.path("data")) {
                    if (!"SWAP".equals(s.path("instType").asText())) continue;
                    if (!"linear".equals(s.path("ctType").asText())) continue;
                    String instId = s.path("instId").asText(); // e.g. BTC-USDT-SWAP
                    String base = instId.split("-")[0];
                    register(base, exchange, instId);
                }
            }
        }
    }

    private void register(String canonical, String exchange, String exchangeSymbol) {
        if (watchedSymbols.isEmpty() || watchedSymbols.contains(canonical)) {
            symbolMap.computeIfAbsent(canonical, k -> new ConcurrentHashMap<>())
                    .put(exchange, exchangeSymbol);
        }
    }

    public boolean hasSymbol(String canonical, String exchange) {
        return symbolMap.getOrDefault(canonical, Map.of()).containsKey(exchange);
    }

    public Optional<String> exchangeSymbol(String canonical, String exchange) {
        return Optional.ofNullable(symbolMap.getOrDefault(canonical, Map.of()).get(exchange));
    }

    public List<String> getWatchedSymbols() {
        return watchedSymbols.isEmpty()
                ? List.copyOf(symbolMap.keySet())
                : watchedSymbols.stream().filter(symbolMap::containsKey).toList();
    }

    public Map<String, String> getExchangeSymbolsFor(String canonical) {
        return Map.copyOf(symbolMap.getOrDefault(canonical, Map.of()));
    }
}
