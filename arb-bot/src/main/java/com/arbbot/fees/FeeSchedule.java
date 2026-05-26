package com.arbbot.fees;

import java.time.Instant;

public record FeeSchedule(
    String exchange,
    String symbol,
    double makerRate,
    double takerRate,
    Instant fetchedAt,
    boolean isStale) {

  /** Conservative defaults used when no API key is configured. */
  public static FeeSchedule defaultFor(String exchange) {
    double taker =
        switch (exchange.toLowerCase()) {
          case "binance" -> 0.0005;
          case "kucoin" -> 0.0006;
          case "bybit" -> 0.0006;
          case "okx" -> 0.0005;
          default -> 0.001;
        };
    return new FeeSchedule(exchange, "*", taker * 0.6, taker, Instant.now(), false);
  }
}
