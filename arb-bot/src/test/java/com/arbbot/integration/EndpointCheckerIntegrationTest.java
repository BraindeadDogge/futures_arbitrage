package com.arbbot.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.arbbot.health.EndpointChecker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class EndpointCheckerIntegrationTest {

  @Test
  void binancePingRespondsWithin2Seconds() {
    var checker = new EndpointChecker("https://fapi.binance.com", "/fapi/v1/ping", 5000);
    var health = checker.check("binance");
    assertTrue(health.restAlive(), "Binance ping failed: " + health.lastError());
    assertTrue(
        health.restLatencyMs() < 2000,
        "Binance latency too high: " + health.restLatencyMs() + "ms");
  }

  @Test
  void bybitPingRespondsWithin2Seconds() {
    var checker = new EndpointChecker("https://api.bybit.com", "/v5/market/time", 5000);
    var health = checker.check("bybit");
    assertTrue(health.restAlive(), "Bybit ping failed: " + health.lastError());
    assertTrue(
        health.restLatencyMs() < 2000,
        "Bybit latency too high: " + health.restLatencyMs() + "ms");
  }
}
