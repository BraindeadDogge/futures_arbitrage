package com.arbbot.health;

import com.arbbot.exchange.ExchangeHealth;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HealthMonitor {

  private static final Logger log = LoggerFactory.getLogger(HealthMonitor.class);

  private final long wsStaleThresholdMs;
  private final Map<String, ExchangeHealth> healthMap = new ConcurrentHashMap<>();

  public HealthMonitor(long wsStaleThresholdMs) {
    this.wsStaleThresholdMs = wsStaleThresholdMs;
  }

  public ExchangeHealth getHealth(String exchange) {
    return healthMap.getOrDefault(exchange, ExchangeHealth.unknown(exchange));
  }

  public void recordWsTick(String exchange) {
    healthMap.merge(
        exchange,
        ExchangeHealth.unknown(exchange).withWsTick(Instant.now()),
        (existing, ignored) -> existing.withWsTick(Instant.now()));
  }

  public void updateRestHealth(String exchange, ExchangeHealth updated) {
    healthMap.merge(
        exchange,
        updated,
        (existing, update) ->
            new ExchangeHealth(
                exchange,
                update.restAlive(),
                existing.wsAlive(),
                update.restLatencyMs(),
                update.lastRestCheck(),
                existing.lastWsTick(),
                existing.dataStale(),
                update.lastError()));
  }

  /** Called periodically — marks feeds stale if no tick received within threshold. */
  public void checkStaleness() {
    Instant threshold = Instant.now().minusMillis(wsStaleThresholdMs);
    healthMap.forEach(
        (exchange, health) -> {
          boolean stale = health.lastWsTick().isBefore(threshold);
          if (stale != health.dataStale()) {
            if (stale) {
              log.warn(
                  "[{}] WS feed marked STALE — no tick in {}ms", exchange, wsStaleThresholdMs);
            } else {
              log.info("[{}] WS feed recovered", exchange);
            }
            healthMap.compute(
                exchange, (k, current) -> current == null ? health.withStale(stale) : current.withStale(stale));
          }
        });
  }

  public boolean isExchangeHealthy(String exchange) {
    ExchangeHealth h = getHealth(exchange);
    return h.restAlive() && !h.dataStale();
  }
}
