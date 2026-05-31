package com.arbbot.exchange.okx;

import com.arbbot.exchange.MultiConnectionWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import okhttp3.OkHttpClient;
import java.util.*;
import java.util.stream.Collectors;

public class OkxWsClient extends MultiConnectionWsClient {

    static final int SYMBOLS_PER_SHARD = 230;

    public OkxWsClient(String wsBaseUrl, Map<String, String> canonicalToOkx,
                       OrderBookManager manager, HealthMonitor healthMonitor,
                       OkHttpClient httpClient) {
        super("okx",
            partition(List.copyOf(canonicalToOkx.entrySet()), SYMBOLS_PER_SHARD).stream()
                .map(entries -> {
                    Map<String, String> slice = new LinkedHashMap<>();
                    entries.forEach(e -> slice.put(e.getKey(), e.getValue()));
                    return new OkxWsClientShard(wsBaseUrl, slice, manager, healthMonitor, httpClient);
                })
                .collect(Collectors.toList()));
    }
}
