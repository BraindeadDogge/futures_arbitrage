package com.arbbot.market;

import java.time.Instant;

public record Tick(
    String canonicalSymbol,
    String exchange,
    double bestBid,
    double bestAsk,
    double effectiveBuyPrice,
    double effectiveSellPrice,
    Instant timestamp,
    boolean isReliable) {

  public static Tick unreliable(String symbol, String exchange) {
    return new Tick(symbol, exchange, 0, 0, 0, 0, Instant.now(), false);
  }
}
