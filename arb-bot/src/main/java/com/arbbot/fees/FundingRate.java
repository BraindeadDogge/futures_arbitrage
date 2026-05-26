package com.arbbot.fees;

import java.time.Instant;

public record FundingRate(
    String exchange,
    String symbol,
    double currentRate,
    double predictedRate,
    Instant nextSettlement,
    Instant fetchedAt) {

  public static FundingRate zero(String exchange, String symbol) {
    return new FundingRate(exchange, symbol, 0.0, 0.0, Instant.MAX, Instant.now());
  }

  /** Returns true if the next funding settlement is within the given number of minutes. */
  public boolean settlesWithin(long minutes) {
    return nextSettlement.isBefore(Instant.now().plusSeconds(minutes * 60));
  }
}
