package com.arbbot.fees;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class FeeEngineTest {

  @Test
  void totalCostIncludesEntryExitAndFunding() {
    var feeEngine = new FeeEngine();
    feeEngine.updateFeeSchedule(
        "binance", "BTC", new FeeSchedule("binance", "BTC", 0.0002, 0.0004, Instant.now(), false));
    feeEngine.updateFeeSchedule(
        "bybit", "BTC", new FeeSchedule("bybit", "BTC", 0.0001, 0.0006, Instant.now(), false));
    feeEngine.updateFundingRate(
        "binance", "BTC", new FundingRate("binance", "BTC", 0.0, 0.0, Instant.MAX, Instant.now()));
    feeEngine.updateFundingRate(
        "bybit", "BTC", new FundingRate("bybit", "BTC", 0.0, 0.0, Instant.MAX, Instant.now()));

    double cost = feeEngine.getTotalRoundTripCost("BTC", "binance", "BTC", "bybit", 0);
    // 2 * (0.0004 + 0.0006) = 0.002 (0.2%)
    assertEquals(0.002, cost, 0.00001);
  }

  @Test
  void fundingCostAddedWhenHoldingHoursPositive() {
    var feeEngine = new FeeEngine();
    feeEngine.updateFeeSchedule(
        "binance", "BTC", new FeeSchedule("binance", "BTC", 0.0002, 0.0004, Instant.now(), false));
    feeEngine.updateFeeSchedule(
        "bybit", "BTC", new FeeSchedule("bybit", "BTC", 0.0001, 0.0006, Instant.now(), false));
    feeEngine.updateFundingRate(
        "binance",
        "BTC",
        new FundingRate("binance", "BTC", 0.0001, 0.0001, Instant.MAX, Instant.now()));
    feeEngine.updateFundingRate(
        "bybit",
        "BTC",
        new FundingRate("bybit", "BTC", 0.0001, 0.0001, Instant.MAX, Instant.now()));

    double cost = feeEngine.getTotalRoundTripCost("BTC", "binance", "BTC", "bybit", 8);
    // base = 0.002, funding: 0.0001 - 0.0001 = 0 net
    assertEquals(0.002, cost, 0.00001);
  }

  @Test
  void fallsBackToDefaultsWhenFeeScheduleMissing() {
    var feeEngine = new FeeEngine();
    feeEngine.updateFundingRate("binance", "BTC", FundingRate.zero("binance", "BTC"));
    feeEngine.updateFundingRate("bybit", "BTC", FundingRate.zero("bybit", "BTC"));

    double cost = feeEngine.getTotalRoundTripCost("BTC", "binance", "BTC", "bybit", 0);
    // Default Binance 0.0005 + Bybit 0.0006, doubled = 0.0022
    assertEquals(0.0022, cost, 0.00001);
  }

  @Test
  void getFeeScheduleReturnsCached() {
    var feeEngine = new FeeEngine();
    var schedule = new FeeSchedule("binance", "BTC", 0.0002, 0.0004, Instant.now(), false);
    feeEngine.updateFeeSchedule("binance", "BTC", schedule);

    var result = feeEngine.getFeeSchedule("BTC", "binance");
    assertTrue(result.isPresent());
    assertEquals(0.0004, result.get().takerRate(), 0.00001);
  }
}
