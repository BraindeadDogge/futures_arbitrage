package com.arbbot.exchange.gate;

import com.arbbot.exchange.MultiConnectionWsClient;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import okhttp3.OkHttpClient;
import java.util.*;
import java.util.stream.Collectors;

public class GateWsClient extends MultiConnectionWsClient {

    static final int SYMBOLS_PER_SHARD = 90;

    public GateWsClient(String wsBaseUrl, Map<String, String> canonicalToGate,
                        OrderBookManager manager, HealthMonitor healthMonitor,
                        OkHttpClient httpClient) {
        super("gate",
            partition(List.copyOf(canonicalToGate.entrySet()), SYMBOLS_PER_SHARD).stream()
                .map(entries -> {
                    Map<String, String> slice = new LinkedHashMap<>();
                    entries.forEach(e -> slice.put(e.getKey(), e.getValue()));
                    return new GateWsClientShard(wsBaseUrl, slice, manager, healthMonitor, httpClient);
                })
                .collect(Collectors.toList()));
    }
}
