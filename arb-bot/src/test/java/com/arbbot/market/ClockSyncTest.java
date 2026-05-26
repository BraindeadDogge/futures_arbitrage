package com.arbbot.market;

import static org.junit.jupiter.api.Assertions.*;

import com.arbbot.util.ClockSync;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;

class ClockSyncTest {

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
    void computesOffsetFromServerTime() {
        long serverTime = System.currentTimeMillis() + 500;
        server.stubFor(
                WireMock.get("/time")
                        .willReturn(
                                WireMock.okJson("{\"serverTime\":" + serverTime + "}")));
        var sync = new ClockSync();
        sync.syncExchange("binance", server.baseUrl() + "/time", "serverTime");
        long offset = sync.getOffsetMs("binance");
        // offset should be approximately 500ms (server is 500ms ahead)
        assertTrue(
                Math.abs(offset - 500) < 200, "Expected ~500ms offset, got " + offset);
    }

    @Test
    void warnIfOffsetExceedsThreshold() {
        long serverTime = System.currentTimeMillis() + 2000;
        server.stubFor(
                WireMock.get("/time")
                        .willReturn(
                                WireMock.okJson("{\"serverTime\":" + serverTime + "}")));
        var sync = new ClockSync();
        boolean warned = sync.syncExchange("binance", server.baseUrl() + "/time", "serverTime");
        assertTrue(warned, "Expected large-offset warning");
    }

    @Test
    void noWarnIfOffsetUnderThreshold() {
        long serverTime = System.currentTimeMillis() + 100;
        server.stubFor(
                WireMock.get("/time")
                        .willReturn(
                                WireMock.okJson("{\"serverTime\":" + serverTime + "}")));
        var sync = new ClockSync();
        boolean warned = sync.syncExchange("binance", server.baseUrl() + "/time", "serverTime");
        assertFalse(warned);
    }

    @Test
    void nowReturnsCorrectedTime() {
        long serverTime = System.currentTimeMillis() + 300;
        server.stubFor(
                WireMock.get("/time")
                        .willReturn(
                                WireMock.okJson("{\"serverTime\":" + serverTime + "}")));
        var sync = new ClockSync();
        sync.syncExchange("binance", server.baseUrl() + "/time", "serverTime");
        long adjusted = sync.now("binance");
        assertTrue(Math.abs(adjusted - serverTime) < 200);
    }
}
