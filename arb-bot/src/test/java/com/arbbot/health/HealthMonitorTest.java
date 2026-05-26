package com.arbbot.health;

import static org.junit.jupiter.api.Assertions.*;

import com.arbbot.exchange.ExchangeHealth;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HealthMonitorTest {

  @Test
  void initialStateIsUnknown() {
    var monitor = new HealthMonitor(60_000);
    var health = monitor.getHealth("binance");
    assertFalse(health.restAlive());
    assertTrue(health.dataStale());
  }

  @Test
  void recordWsTickUpdatesLastTick() {
    var monitor = new HealthMonitor(60_000);
    monitor.recordWsTick("binance");
    var health = monitor.getHealth("binance");
    assertFalse(health.dataStale());
    assertTrue(health.lastWsTick().isAfter(Instant.EPOCH));
  }

  @Test
  void staleAfterThreshold() throws InterruptedException {
    var monitor = new HealthMonitor(50); // 50ms threshold
    monitor.recordWsTick("binance");
    Thread.sleep(100);
    monitor.checkStaleness();
    var health = monitor.getHealth("binance");
    assertTrue(health.dataStale());
  }

  @Test
  void notStaleBeforeThreshold() throws InterruptedException {
    var monitor = new HealthMonitor(5000); // 5s threshold
    monitor.recordWsTick("binance");
    Thread.sleep(10);
    monitor.checkStaleness();
    var health = monitor.getHealth("binance");
    assertFalse(health.dataStale());
  }

  @Test
  void updateRestHealthUpdatesState() {
    var monitor = new HealthMonitor(60_000);
    var alive = ExchangeHealth.unknown("binance").withRestAlive(true, 42L, Instant.now());
    monitor.updateRestHealth("binance", alive);
    assertTrue(monitor.getHealth("binance").restAlive());
    assertEquals(42L, monitor.getHealth("binance").restLatencyMs());
  }

  @Test
  void isExchangeHealthyRequiresBothRestAndFreshFeed() throws InterruptedException {
    var monitor = new HealthMonitor(5000);
    // No REST alive yet
    assertFalse(monitor.isExchangeHealthy("binance"));
    // Set REST alive
    monitor.updateRestHealth(
        "binance", ExchangeHealth.unknown("binance").withRestAlive(true, 10L, Instant.now()));
    // Still not healthy — no WS tick yet
    assertFalse(monitor.isExchangeHealthy("binance"));
    // Record WS tick
    monitor.recordWsTick("binance");
    monitor.checkStaleness();
    assertTrue(monitor.isExchangeHealthy("binance"));
  }
}
