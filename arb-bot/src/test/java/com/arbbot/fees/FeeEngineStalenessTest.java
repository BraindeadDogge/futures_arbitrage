package com.arbbot.fees;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class FeeEngineStalenessTest {

  @Test
  void scheduleIsNotStaleWhenFresh() {
    var schedule = new FeeSchedule("binance", "BTC", 0.0002, 0.0004, Instant.now(), false);
    assertFalse(schedule.isStale());
  }

  @Test
  void scheduleMarkedStaleWhenFlagIsTrue() {
    var schedule =
        new FeeSchedule("binance", "BTC", 0.0002, 0.0004, Instant.now().minusSeconds(3600), true);
    assertTrue(schedule.isStale());
  }

  @Test
  void feeEngineMarksStaleAfterRefreshInterval() {
    var engine = new FeeEngine();
    var oldSchedule =
        new FeeSchedule("binance", "BTC", 0.0002, 0.0004, Instant.now().minusSeconds(7200), false);
    engine.updateFeeSchedule("binance", "BTC", oldSchedule);
    engine.markStaleIfOlderThan(3600);
    var retrieved = engine.getFeeSchedule("BTC", "binance").orElseThrow();
    assertTrue(retrieved.isStale());
  }
}
