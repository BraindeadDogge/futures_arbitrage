package com.arbbot.exchange;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class BaseWsClient extends WebSocketListener implements Exchange {

    private static final Logger log = LoggerFactory.getLogger(BaseWsClient.class);
    private static final long MAX_BACKOFF_MS = 30_000;

    protected final String exchangeName;
    protected final OkHttpClient httpClient;
    private volatile WebSocket webSocket;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    protected BaseWsClient(String exchangeName, OkHttpClient httpClient) {
        this.exchangeName = exchangeName;
        this.httpClient = httpClient;
    }

    @Override
    public String name() { return exchangeName; }

    @Override
    public void connect() {
        shouldReconnect.set(true);
        doConnect();
    }

    protected abstract String wsUrl();

    protected abstract void onConnected(WebSocket ws);

    protected abstract void handleMessage(WebSocket ws, String text);

    private void doConnect() {
        String url = wsUrl();
        log.info("[{}] Connecting to {}", exchangeName, url);
        Request request = new Request.Builder().url(url).build();
        webSocket = httpClient.newWebSocket(request, this);
    }

    @Override
    public final void onOpen(WebSocket ws, Response response) {
        log.info("[{}] WebSocket connected", exchangeName);
        connected.set(true);
        reconnectAttempts.set(0);
        onConnected(ws);
    }

    @Override
    public final void onMessage(WebSocket ws, String text) {
        handleMessage(ws, text);
    }

    @Override
    public final void onMessage(WebSocket ws, ByteString bytes) {
        // binary frames ignored by default
    }

    @Override
    public final void onClosing(WebSocket ws, int code, String reason) {
        log.warn("[{}] WebSocket closing: {} {}", exchangeName, code, reason);
        connected.set(false);
    }

    @Override
    public final void onClosed(WebSocket ws, int code, String reason) {
        log.warn("[{}] WebSocket closed: {} {}", exchangeName, code, reason);
        connected.set(false);
        scheduleReconnect();
    }

    @Override
    public final void onFailure(WebSocket ws, Throwable t, Response response) {
        log.error("[{}] WebSocket failure: {}", exchangeName, t.getMessage());
        connected.set(false);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!shouldReconnect.get()) return;
        int attempt = reconnectAttempts.incrementAndGet();
        long backoff = Math.min(100L * (1L << Math.min(attempt - 1, 8)), MAX_BACKOFF_MS);
        log.info("[{}] Reconnecting in {}ms (attempt {})", exchangeName, backoff, attempt);
        Thread.ofVirtual().name(exchangeName + "-reconnect").start(() -> {
            try {
                Thread.sleep(backoff);
                if (shouldReconnect.get()) doConnect();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    protected void send(String message) {
        if (webSocket != null && connected.get()) {
            webSocket.send(message);
        }
    }

    @Override
    public void disconnect() {
        shouldReconnect.set(false);
        if (webSocket != null) webSocket.close(1000, "shutdown");
        connected.set(false);
    }

    @Override
    public boolean isConnected() { return connected.get(); }
}
