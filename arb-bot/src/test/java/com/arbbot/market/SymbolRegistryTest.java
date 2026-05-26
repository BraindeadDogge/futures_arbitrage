package com.arbbot.market;

import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import org.junit.jupiter.api.*;

class SymbolRegistryTest {

    private WireMockServer server;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void mapsBinanceSymbolToCanonical() {
        server.stubFor(
                WireMock.get(WireMock.urlPathEqualTo("/fapi/v1/exchangeInfo"))
                        .willReturn(
                                WireMock.okJson(
                                        """
                        {"symbols":[
                          {"symbol":"BTCUSDT","baseAsset":"BTC","contractType":"PERPETUAL","marginAsset":"USDT","status":"TRADING"},
                          {"symbol":"BTCUSD_PERP","baseAsset":"BTC","contractType":"PERPETUAL","marginAsset":"BTC","status":"TRADING"}
                        ]}""")));

        var registry = new SymbolRegistry();
        registry.loadExchange(
                "binance",
                server.baseUrl() + "/fapi/v1/exchangeInfo",
                SymbolRegistry.ExchangeFormat.BINANCE);

        assertTrue(registry.hasSymbol("BTC", "binance"));
        assertEquals("BTCUSDT", registry.exchangeSymbol("BTC", "binance").orElseThrow());
    }

    @Test
    void excludesInverseContractsFromBinance() {
        server.stubFor(
                WireMock.get(WireMock.urlPathEqualTo("/fapi/v1/exchangeInfo"))
                        .willReturn(
                                WireMock.okJson(
                                        """
                        {"symbols":[
                          {"symbol":"BTCUSD_PERP","baseAsset":"BTC","contractType":"PERPETUAL","marginAsset":"BTC","status":"TRADING"}
                        ]}""")));

        var registry = new SymbolRegistry();
        registry.loadExchange(
                "binance",
                server.baseUrl() + "/fapi/v1/exchangeInfo",
                SymbolRegistry.ExchangeFormat.BINANCE);

        assertFalse(registry.hasSymbol("BTC", "binance"), "Inverse BTCUSD_PERP must be excluded");
    }

    @Test
    void getWatchedSymbolsReturnsOnlyConfigured() {
        server.stubFor(
                WireMock.get(WireMock.urlPathEqualTo("/fapi/v1/exchangeInfo"))
                        .willReturn(
                                WireMock.okJson(
                                        """
                        {"symbols":[
                          {"symbol":"BTCUSDT","baseAsset":"BTC","contractType":"PERPETUAL","marginAsset":"USDT","status":"TRADING"},
                          {"symbol":"ETHUSDT","baseAsset":"ETH","contractType":"PERPETUAL","marginAsset":"USDT","status":"TRADING"},
                          {"symbol":"SOLUSDT","baseAsset":"SOL","contractType":"PERPETUAL","marginAsset":"USDT","status":"TRADING"}
                        ]}""")));

        var registry = new SymbolRegistry();
        registry.setWatchedSymbols(List.of("BTC", "ETH"));
        registry.loadExchange(
                "binance",
                server.baseUrl() + "/fapi/v1/exchangeInfo",
                SymbolRegistry.ExchangeFormat.BINANCE);

        var watched = registry.getWatchedSymbols();
        assertTrue(watched.contains("BTC"));
        assertTrue(watched.contains("ETH"));
        assertFalse(watched.contains("SOL"));
    }
}
