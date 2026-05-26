package com.arbbot.health;

import static org.junit.jupiter.api.Assertions.*;

import com.arbbot.exchange.ExchangeHealth;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;

class EndpointCheckerTest {

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
  void healthyEndpointReturnsAliveTrue() {
    server.stubFor(WireMock.get("/fapi/v1/ping").willReturn(WireMock.ok("{}")));
    var checker = new EndpointChecker(server.baseUrl(), "/fapi/v1/ping", 3000);
    ExchangeHealth health = checker.check("binance");
    assertTrue(health.restAlive());
    assertTrue(health.restLatencyMs() >= 0);
  }

  @Test
  void serverErrorReturnsAliveFalse() {
    server.stubFor(WireMock.get("/fapi/v1/ping").willReturn(WireMock.serverError()));
    var checker = new EndpointChecker(server.baseUrl(), "/fapi/v1/ping", 3000);
    ExchangeHealth health = checker.check("binance");
    assertFalse(health.restAlive());
    assertNotNull(health.lastError());
  }

  @Test
  void malformedJsonStillAliveIfStatus200() {
    server.stubFor(WireMock.get("/fapi/v1/ping").willReturn(WireMock.ok("not-json")));
    var checker = new EndpointChecker(server.baseUrl(), "/fapi/v1/ping", 3000);
    ExchangeHealth health = checker.check("binance");
    // 200 OK = REST endpoint alive, regardless of body
    assertTrue(health.restAlive());
  }

  @Test
  void timeoutReturnsAliveFalse() {
    server.stubFor(
        WireMock.get("/fapi/v1/ping").willReturn(WireMock.ok("{}").withFixedDelay(500)));
    var checker = new EndpointChecker(server.baseUrl(), "/fapi/v1/ping", 100); // 100ms timeout
    ExchangeHealth health = checker.check("binance");
    assertFalse(health.restAlive());
  }
}
