package com.arbbot.exchange.bybit;

import com.arbbot.exchange.MultiConnectionWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import okhttp3.OkHttpClient;
import java.util.List;
import java.util.stream.Collectors;

public class BybitWsClient extends MultiConnectionWsClient {

    static final int SYMBOLS_PER_SHARD = 10;

    public BybitWsClient(String wsBaseUrl, List<String> symbols,
                         OrderBookManager manager, HealthMonitor healthMonitor,
                         OkHttpClient httpClient) {
        super("bybit",
            partition(symbols, SYMBOLS_PER_SHARD).stream()
                .map(slice -> new BybitWsClientShard(wsBaseUrl, slice, manager, healthMonitor, httpClient))
                .collect(Collectors.toList()));
    }
}
