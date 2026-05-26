package com.arbbot.scanner;

import static org.junit.jupiter.api.Assertions.*;
import com.arbbot.fees.FeeEngine;
import com.arbbot.fees.FeeSchedule;
import com.arbbot.fees.FundingRate;
import com.arbbot.market.Tick;
import org.junit.jupiter.api.Test;
import java.time.Instant;

class SpreadCalculatorTest {

    private FeeEngine feeEngineWith(double binanceTaker, double bybitTaker) {
        var engine = new FeeEngine();
        engine.updateFeeSchedule("binance", "BTC",
            new FeeSchedule("binance", "BTC", binanceTaker * 0.5, binanceTaker, Instant.now(), false));
        engine.updateFeeSchedule("bybit", "BTC",
            new FeeSchedule("bybit", "BTC", bybitTaker * 0.5, bybitTaker, Instant.now(), false));
        engine.updateFundingRate("binance", "BTC", FundingRate.zero("binance", "BTC"));
        engine.updateFundingRate("bybit", "BTC", FundingRate.zero("bybit", "BTC"));
        return engine;
    }

    @Test
    void grossSpreadComputedCorrectly() {
        var buyTick = new Tick("BTC", "binance", 49999, 50000, 50000, 49999, Instant.now(), true);
        var sellTick = new Tick("BTC", "bybit", 50100, 50101, 50101, 50100, Instant.now(), true);

        double gross = SpreadCalculator.grossSpread(buyTick, sellTick);
        assertEquals(0.002, gross, 0.00001);
    }

    @Test
    void netSpreadSubtractsFees() {
        var buyTick = new Tick("BTC", "binance", 49999, 50000, 50000, 49999, Instant.now(), true);
        var sellTick = new Tick("BTC", "bybit", 50100, 50101, 50101, 50100, Instant.now(), true);
        // Gross = 0.2%, fees = 2*(0.0004+0.0006) = 0.002 → net = 0.0
        var engine = feeEngineWith(0.0004, 0.0006);

        double net = SpreadCalculator.netSpread(buyTick, sellTick, engine, 4.0);
        assertEquals(0.0, net, 0.00001);
    }

    @Test
    void negativeSpreadExcluded() {
        var buyTick = new Tick("BTC", "binance", 50099, 50100, 50100, 50099, Instant.now(), true);
        var sellTick = new Tick("BTC", "bybit", 50000, 50001, 50001, 50000, Instant.now(), true);

        double gross = SpreadCalculator.grossSpread(buyTick, sellTick);
        assertTrue(gross < 0);
    }

    @Test
    void zeroEffectivePriceReturnsNaN() {
        var buyTick = new Tick("BTC", "binance", 0, 0, 0, 0, Instant.now(), false);
        var sellTick = new Tick("BTC", "bybit", 50100, 50101, 50101, 50100, Instant.now(), true);

        double gross = SpreadCalculator.grossSpread(buyTick, sellTick);
        assertFalse(Double.isFinite(gross) && gross > 0);
    }
}
