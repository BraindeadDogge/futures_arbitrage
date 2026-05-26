package com.arbbot.fees;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class FundingCostDirectionTest {

  @Test
  void longPaysFundingWhenRatePositive() {
    var feeEngine = new FeeEngine();
    feeEngine.updateFeeSchedule(
        "a", "BTC", new FeeSchedule("a", "BTC", 0.0002, 0.0004, Instant.now(), false));
    feeEngine.updateFeeSchedule(
        "b", "BTC", new FeeSchedule("b", "BTC", 0.0002, 0.0004, Instant.now(), false));
    feeEngine.updateFundingRate(
        "a", "BTC", new FundingRate("a", "BTC", 0.0003, 0.0003, Instant.MAX, Instant.now()));
    feeEngine.updateFundingRate(
        "b", "BTC", new FundingRate("b", "BTC", 0.0001, 0.0001, Instant.MAX, Instant.now()));

    double cost = feeEngine.getTotalRoundTripCost("BTC", "a", "BTC", "b", 8);
    double tradingFees = 2 * (0.0004 + 0.0004);
    double expectedFunding = (0.0003 - 0.0001) * (8.0 / 8.0);
    assertEquals(tradingFees + expectedFunding, cost, 0.00001);
  }

  @Test
  void fundingScalesWithHoldingPeriod() {
    var feeEngine = new FeeEngine();
    feeEngine.updateFeeSchedule(
        "a", "BTC", new FeeSchedule("a", "BTC", 0.0002, 0.0004, Instant.now(), false));
    feeEngine.updateFeeSchedule(
        "b", "BTC", new FeeSchedule("b", "BTC", 0.0002, 0.0004, Instant.now(), false));
    feeEngine.updateFundingRate(
        "a", "BTC", new FundingRate("a", "BTC", 0.0001, 0.0001, Instant.MAX, Instant.now()));
    feeEngine.updateFundingRate(
        "b", "BTC", new FundingRate("b", "BTC", 0.0, 0.0, Instant.MAX, Instant.now()));

    double cost8h = feeEngine.getTotalRoundTripCost("BTC", "a", "BTC", "b", 8);
    double cost16h = feeEngine.getTotalRoundTripCost("BTC", "a", "BTC", "b", 16);
    double diff = cost16h - cost8h;
    assertEquals(0.0001, diff, 0.000001);
  }
}
