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
import java.util.concurrent.atomic.AtomicLong;

public abstract class BaseWsClient extends WebSocketListener implements Exchange {

    private static final Logger log = LoggerFactory.getLogger(BaseWsClient.class);
    private static final long MAX_BACKOFF_MS     = 30_000;
    private static final long WATCHDOG_CHECK_MS  =  5_000;  // poll interval
    private static final long WATCHDOG_STALE_MS  = 15_000;  // trigger reconnect if no message

    protected final String exchangeName;
    protected final OkHttpClient httpClient;
    private volatile WebSocket webSocket;
    private final AtomicBoolean connected         = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect   = new AtomicBoolean(true);
    private final AtomicBoolean reconnectPending  = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicLong    lastMessageAt     = new AtomicLong(0);
    private volatile Thread     watchdogThread;

    protected BaseWsClient(String exchangeName, OkHttpClient httpClient) {
        this.exchangeName = exchangeName;
        this.httpClient = httpClient;
    }

    @Override
    public String name() { return exchangeName; }

    @Override
    public void connect() {
        shouldReconnect.set(true);
        lastMessageAt.set(System.currentTimeMillis()); // seed so watchdog doesn't fire before first message
        startWatchdog();
        doConnect();
    }

    private void startWatchdog() {
        if (watchdogThread != null && watchdogThread.isAlive()) return;
        watchdogThread = Thread.ofVirtual().name(exchangeName + "-watchdog").start(() -> {
            while (shouldReconnect.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(WATCHDOG_CHECK_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!connected.get() || !shouldReconnect.get()) continue;
                long idleMs = System.currentTimeMillis() - lastMessageAt.get();
                if (idleMs > WATCHDOG_STALE_MS) {
                    log.warn("[{}] Watchdog: no message for {}s — forcing reconnect",
                             exchangeName, idleMs / 1000);
                    forceReconnect();
                }
            }
        });
    }

    /** Immediate reconnect used by the watchdog for silently-dead connections. */
    private void forceReconnect() {
        connected.set(false);
        WebSocket ws = webSocket;
        if (ws != null) ws.cancel(); // cancel() closes TCP immediately — no close frame needed
        onBeforeReconnect();
        scheduleReconnect();
    }

    /**
     * Called just before a watchdog-triggered reconnect.
     * Subclasses override to flush per-symbol buffering state so the new session starts clean.
     */
    protected void onBeforeReconnect() {}

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
        reconnectPending.set(false);
        lastMessageAt.set(System.currentTimeMillis());
        onConnected(ws);
    }

    @Override
    public final void onMessage(WebSocket ws, String text) {
        lastMessageAt.set(System.currentTimeMillis());
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
        scheduleReconnect(); // don't wait for onClosed which can take minutes
    }

    @Override
    public final void onClosed(WebSocket ws, int code, String reason) {
        log.warn("[{}] WebSocket closed: {} {}", exchangeName, code, reason);
        connected.set(false);
        scheduleReconnect();
    }

    @Override
    public final void onFailure(WebSocket ws, Throwable t, Response response) {
        String detail = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        if (response != null) {
            log.error("[{}] WebSocket failure: {} (HTTP {})", exchangeName, detail, response.code());
        } else {
            log.error("[{}] WebSocket failure: {}", exchangeName, detail, t);
        }
        connected.set(false);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!shouldReconnect.get()) return;
        if (!reconnectPending.compareAndSet(false, true)) return; // already scheduled
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
        Thread wdt = watchdogThread;
        if (wdt != null) wdt.interrupt();
        if (webSocket != null) webSocket.close(1000, "shutdown");
        connected.set(false);
    }

    @Override
    public boolean isConnected() { return connected.get(); }
}
