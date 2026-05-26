package com.arbbot.fees;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FeeEngine {

  private static final Logger log = LoggerFactory.getLogger(FeeEngine.class);

  // key: "exchange:symbol"
  private final Map<String, FeeSchedule> feeCache = new ConcurrentHashMap<>();
  private final Map<String, FundingRate> fundingCache = new ConcurrentHashMap<>();

  public void updateFeeSchedule(String exchange, String canonicalSymbol, FeeSchedule schedule) {
    feeCache.put(key(exchange, canonicalSymbol), schedule);
  }

  public void updateFundingRate(String exchange, String canonicalSymbol, FundingRate rate) {
    fundingCache.put(key(exchange, canonicalSymbol), rate);
  }

  public Optional<FeeSchedule> getFeeSchedule(String canonicalSymbol, String exchange) {
    return Optional.ofNullable(feeCache.get(key(exchange, canonicalSymbol)));
  }

  public Optional<FundingRate> getFundingRate(String canonicalSymbol, String exchange) {
    return Optional.ofNullable(fundingCache.get(key(exchange, canonicalSymbol)));
  }

  public double getTotalRoundTripCost(
      String symbolA,
      String exchangeA,
      String symbolB,
      String exchangeB,
      double holdingHours) {
    double takerA =
        getFeeSchedule(symbolA, exchangeA)
            .map(FeeSchedule::takerRate)
            .orElseGet(
                () -> {
                  log.warn(
                      "[{}] Fee schedule missing for {}, using default", exchangeA, symbolA);
                  return FeeSchedule.defaultFor(exchangeA).takerRate();
                });

    double takerB =
        getFeeSchedule(symbolB, exchangeB)
            .map(FeeSchedule::takerRate)
            .orElseGet(
                () -> {
                  log.warn(
                      "[{}] Fee schedule missing for {}, using default", exchangeB, symbolB);
                  return FeeSchedule.defaultFor(exchangeB).takerRate();
                });

    double tradingFees = 2.0 * (takerA + takerB);

    double periods = holdingHours / 8.0;
    double fundingA =
        getFundingRate(symbolA, exchangeA).map(FundingRate::currentRate).orElse(0.0);
    double fundingB =
        getFundingRate(symbolB, exchangeB).map(FundingRate::currentRate).orElse(0.0);
    double netFunding = (fundingA - fundingB) * periods;

    return tradingFees + netFunding;
  }

  public void markStaleIfOlderThan(long thresholdSeconds) {
    Instant cutoff = Instant.now().minusSeconds(thresholdSeconds);
    feeCache.replaceAll(
        (key, schedule) -> {
          if (!schedule.isStale() && schedule.fetchedAt().isBefore(cutoff)) {
            log.warn(
                "Fee schedule for {} is stale (fetched at {})", key, schedule.fetchedAt());
            return new FeeSchedule(
                schedule.exchange(),
                schedule.symbol(),
                schedule.makerRate(),
                schedule.takerRate(),
                schedule.fetchedAt(),
                true);
          }
          return schedule;
        });
  }

  private static String key(String exchange, String symbol) {
    return exchange + ":" + symbol;
  }
}
