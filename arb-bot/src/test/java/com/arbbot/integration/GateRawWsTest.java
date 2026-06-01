package com.arbbot.integration;

import okhttp3.*;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Raw WebSocket diagnostic — prints every message Gate.io sends for 30s.
 * Run with: ./gradlew test -Dtest.tags=integration --tests "*.GateRawWsTest"
 */
@Tag("integration")
class GateRawWsTest {

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void printRawGateMessages() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

        Request req = new Request.Builder()
            .url("wss://fx-ws.gateio.ws/v4/ws/usdt")
            .build();

        http.newWebSocket(req, new WebSocketListener() {
            int msgCount = 0;

            @Override
            public void onOpen(WebSocket ws, Response response) {
                System.out.println("[raw] Connected");
                long t = System.currentTimeMillis() / 1000;
                // Subscribe to BTC_USDT order book updates
                String sub = "{\"time\":" + t + ",\"channel\":\"futures.order_book_update\","
                    + "\"event\":\"subscribe\",\"payload\":[\"BTC_USDT\",\"100ms\",\"20\"]}";
                System.out.println("[raw] Sending: " + sub);
                ws.send(sub);

                // Stop after 30s
                Thread.ofVirtual().start(() -> {
                    try { Thread.sleep(30_000); } catch (InterruptedException ignored) {}
                    ws.close(1000, "done");
                    done.countDown();
                });
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                msgCount++;
                // Print first 20 messages in full, then just channel/event/size
                if (msgCount <= 20) {
                    System.out.println("[raw] MSG #" + msgCount + ": " + text);
                } else if (msgCount <= 30) {
                    // print summary
                    String preview = text.length() > 200 ? text.substring(0, 200) + "..." : text;
                    System.out.println("[raw] MSG #" + msgCount + " (len=" + text.length() + "): " + preview);
                } else {
                    System.out.println("[raw] MSG #" + msgCount + " len=" + text.length());
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                System.out.println("[raw] FAILURE: " + t.getMessage());
                done.countDown();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                System.out.println("[raw] Closed: " + code + " " + reason + " total messages=" + msgCount);
                done.countDown();
            }
        });

        done.await(60, TimeUnit.SECONDS);
        System.out.println("[raw] Test done");
    }
}
