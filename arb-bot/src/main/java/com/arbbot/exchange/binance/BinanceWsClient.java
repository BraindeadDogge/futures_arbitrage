package com.arbbot.exchange.binance;

import com.arbbot.exchange.MultiConnectionWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import okhttp3.OkHttpClient;
import java.util.List;
import java.util.stream.Collectors;

public class BinanceWsClient extends MultiConnectionWsClient {

    static final int SYMBOLS_PER_SHARD = 190;

    public BinanceWsClient(String wsBaseUrl, String restBaseUrl, List<String> symbols,
                           OrderBookManager manager, HealthMonitor healthMonitor,
                           OkHttpClient httpClient) {
        super("binance",
            partition(symbols, SYMBOLS_PER_SHARD).stream()
                .map(slice -> new BinanceWsClientShard(wsBaseUrl, restBaseUrl, slice, manager, healthMonitor, httpClient))
                .collect(Collectors.toList()));
    }
}
