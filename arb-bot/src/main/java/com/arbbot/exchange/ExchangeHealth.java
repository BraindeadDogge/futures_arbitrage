package com.arbbot.exchange;

import java.time.Instant;

public record ExchangeHealth(
    String exchange,
    boolean restAlive,
    boolean wsAlive,
    long restLatencyMs,
    Instant lastRestCheck,
    Instant lastWsTick,
    boolean dataStale,
    String lastError) {

  public static ExchangeHealth unknown(String exchange) {
    return new ExchangeHealth(
        exchange, false, false, -1, Instant.EPOCH, Instant.EPOCH, true, "not yet checked");
  }

  public ExchangeHealth withRestAlive(boolean alive, long latencyMs, Instant checkedAt) {
    return new ExchangeHealth(
        exchange, alive, wsAlive, latencyMs, checkedAt, lastWsTick, dataStale,
        alive ? null : lastError);
  }

  public ExchangeHealth withWsTick(Instant tickAt) {
    return new ExchangeHealth(
        exchange, restAlive, true, restLatencyMs, lastRestCheck, tickAt, false, lastError);
  }

  public ExchangeHealth withStale(boolean stale) {
    return new ExchangeHealth(
        exchange, restAlive, wsAlive, restLatencyMs, lastRestCheck, lastWsTick, stale, lastError);
  }

  public ExchangeHealth withError(String error) {
    return new ExchangeHealth(
        exchange, false, false, restLatencyMs, lastRestCheck, lastWsTick, true, error);
  }
}
