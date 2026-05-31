package com.arbbot.exchange.kucoin;

import com.arbbot.exchange.MultiConnectionWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import okhttp3.OkHttpClient;
import java.util.*;
import java.util.stream.Collectors;

public class KuCoinWsClient extends MultiConnectionWsClient {

    static final int SYMBOLS_PER_SHARD = 290;

    public KuCoinWsClient(String restBaseUrl, Map<String, String> canonicalToKucoin,
                          OrderBookManager manager, HealthMonitor healthMonitor,
                          OkHttpClient httpClient) {
        super("kucoin",
            partition(List.copyOf(canonicalToKucoin.entrySet()), SYMBOLS_PER_SHARD).stream()
                .map(entries -> {
                    Map<String, String> slice = new LinkedHashMap<>();
                    entries.forEach(e -> slice.put(e.getKey(), e.getValue()));
                    return new KuCoinWsClientShard(restBaseUrl, slice, manager, healthMonitor, httpClient);
                })
                .collect(Collectors.toList()));
    }
}
