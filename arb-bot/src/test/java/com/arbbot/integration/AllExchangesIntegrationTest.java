package com.arbbot.integration;

import com.arbbot.exchange.binance.BinanceWsClient;
import com.arbbot.exchange.bitget.BitgetWsClient;
import com.arbbot.exchange.bybit.BybitWsClient;
import com.arbbot.exchange.gate.GateWsClient;
import com.arbbot.exchange.htx.HtxWsClient;
import com.arbbot.exchange.kucoin.KuCoinWsClient;
import com.arbbot.exchange.okx.OkxWsClient;
import com.arbbot.exchange.Exchange;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBookManager;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test — verifies all 7 exchanges initialize at least 1 book within 60s.
 * Does not do a 2-minute stability check; use the per-exchange tests for that.
 */
@Tag("integration")
class AllExchangesIntegrationTest {

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void allExchangesInitializeWithinTimeout() throws InterruptedException {
        var obm = new OrderBookManager(5000);
        var health = new HealthMonitor(5000);
        var http = new OkHttpClient();

        Map<String, String> canonicalToOkx = Map.of("BTC", "BTC-USDT-SWAP");
        Map<String, String> canonicalToGate = Map.of("BTC", "BTC_USDT");
        Map<String, String> canonicalToKucoin = Map.of("BTC", "XBTUSDTM");

        List<Exchange> clients = List.of(
            new BinanceWsClient("wss://fstream.binance.com", "https://fapi.binance.com",
                List.of("BTCUSDT"), obm, health, http),
            new BybitWsClient("wss://stream.bybit.com/v5/public/linear",
                List.of("BTCUSDT"), obm, health, http),
            new OkxWsClient("wss://ws.okx.com:8443/ws/v5/public",
                canonicalToOkx, obm, health, http),
            new KuCoinWsClient("https://api-futures.kucoin.com",
                canonicalToKucoin, obm, health, http),
            new GateWsClient("wss://fx-ws.gateio.ws/v4/ws/usdt",
                canonicalToGate, obm, health, http),
            new BitgetWsClient("wss://ws.bitget.com/v2/ws/public",
                List.of("BTCUSDT"), obm, health, http),
            new HtxWsClient("wss://api.hbdm.com/linear-swap-ws",
                List.of("BTC-USDT"), obm, health, http)
        );

        // Map of exchange name → book key for the one symbol we subscribed
        Map<String, String> bookKeys = Map.of(
            "binance", "BTCUSDT",
            "bybit",   "BTCUSDT",
            "okx",     "BTC-USDT-SWAP",
            "kucoin",  "XBTUSDTM",
            "gate",    "BTC_USDT",
            "bitget",  "BTCUSDT",
            "htx",     "BTC-USDT"
        );

        clients.forEach(Exchange::connect);
        try {
            long deadline = System.currentTimeMillis() + 90_000;
            while (System.currentTimeMillis() < deadline) {
                boolean allInit = bookKeys.entrySet().stream()
                    .allMatch(e -> obm.getOrCreateBook(e.getKey(), e.getValue()).isInitialized());
                if (allInit) break;
                Thread.sleep(500);
            }

            List<String> notInit = new ArrayList<>();
            for (var e : bookKeys.entrySet()) {
                if (!obm.getOrCreateBook(e.getKey(), e.getValue()).isInitialized())
                    notInit.add(e.getKey());
            }
            assertTrue(notInit.isEmpty(),
                "These exchanges did not initialize within 90s: " + notInit);
        } finally {
            clients.forEach(Exchange::disconnect);
        }
    }
}
