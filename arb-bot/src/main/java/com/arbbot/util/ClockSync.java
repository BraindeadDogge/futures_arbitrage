package com.arbbot.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClockSync {

    private static final Logger log = LoggerFactory.getLogger(ClockSync.class);
    private static final long WARN_THRESHOLD_MS = 500;
    private static final ObjectMapper mapper = new ObjectMapper();

    private final OkHttpClient httpClient;
    private final Map<String, Long> offsets = new ConcurrentHashMap<>();

    public ClockSync() {
        this.httpClient = new OkHttpClient();
    }

    public ClockSync(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Syncs clock for exchange. Returns true if offset exceeds WARN_THRESHOLD_MS. The fieldPath is
     * the JSON field name containing the server timestamp in milliseconds.
     */
    public boolean syncExchange(String exchange, String url, String fieldPath) {
        try {
            long before = System.currentTimeMillis();
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = httpClient.newCall(req).execute()) {
                long after = System.currentTimeMillis();
                long rtt = after - before;
                JsonNode root = mapper.readTree(resp.body().string());
                long serverTime = root.path(fieldPath).asLong();
                // Estimate: serverTime is sampled at before + rtt/2
                long offset = serverTime - (before + rtt / 2);
                offsets.put(exchange, offset);
                boolean warn = Math.abs(offset) > WARN_THRESHOLD_MS;
                if (warn) {
                    log.warn(
                            "[{}] Clock offset {}ms exceeds {}ms threshold — order placement may"
                                    + " fail in Phase 2",
                            exchange,
                            offset,
                            WARN_THRESHOLD_MS);
                } else {
                    log.info("[{}] Clock offset: {}ms", exchange, offset);
                }
                return warn;
            }
        } catch (Exception e) {
            log.error("[{}] Clock sync failed: {}", exchange, e.getMessage());
            offsets.put(exchange, 0L);
            return false;
        }
    }

    public long getOffsetMs(String exchange) {
        return offsets.getOrDefault(exchange, 0L);
    }

    public long now(String exchange) {
        return System.currentTimeMillis() + offsets.getOrDefault(exchange, 0L);
    }
}
