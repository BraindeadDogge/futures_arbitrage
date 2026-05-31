package com.arbbot.exchange;

import java.util.ArrayList;
import java.util.List;

public abstract class MultiConnectionWsClient implements Exchange {

    private final String exchangeName;
    protected final List<BaseWsClient> shards;

    protected MultiConnectionWsClient(String exchangeName, List<BaseWsClient> shards) {
        this.exchangeName = exchangeName;
        this.shards = List.copyOf(shards);
    }

    @Override public String name() { return exchangeName; }
    @Override public void connect() { shards.forEach(BaseWsClient::connect); }
    @Override public void disconnect() { shards.forEach(BaseWsClient::disconnect); }
    @Override public boolean isConnected() {
        return shards.stream().anyMatch(BaseWsClient::isConnected);
    }
    public int shardCount() { return shards.size(); }

    protected static <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size)
            result.add(items.subList(i, Math.min(i + size, items.size())));
        return result;
    }
}
