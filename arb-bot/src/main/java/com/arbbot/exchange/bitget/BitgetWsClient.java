package com.arbbot.exchange.bitget;

import com.arbbot.exchange.MultiConnectionWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import okhttp3.OkHttpClient;
import java.util.List;
import java.util.stream.Collectors;

public class BitgetWsClient extends MultiConnectionWsClient {

    static final int SYMBOLS_PER_SHARD = 90;

    public BitgetWsClient(String wsBaseUrl, List<String> symbols,
                          OrderBookManager manager, HealthMonitor healthMonitor,
                          OkHttpClient httpClient) {
        super("bitget",
            partition(symbols, SYMBOLS_PER_SHARD).stream()
                .map(slice -> new BitgetWsClientShard(wsBaseUrl, slice, manager, healthMonitor, httpClient))
                .collect(Collectors.toList()));
    }
}
