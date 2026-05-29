package com.arbbot.scanner;

import static org.junit.jupiter.api.Assertions.*;
import com.arbbot.config.AppConfig;
import com.arbbot.fees.FundingRate;
import com.arbbot.market.Tick;
import org.junit.jupiter.api.Test;
import java.time.Instant;

class RiskFilterTest {

    private AppConfig.RiskConfig riskConfig() {
        return new AppConfig.RiskConfig(0.1, 5);
    }

    @Test
    void passesWhenAllConditionsMet() {
        var filter = new com.arbbot.risk.RiskFilter(riskConfig());
        var longTick = new Tick("BTC", "binance", 50000, 50001, 50001, 50000, 0, 0, Instant.now(), true);
        var shortTick = new Tick("BTC", "bybit", 50100, 50101, 50101, 50100, 0, 0, Instant.now(), true);
        var longFunding = new FundingRate("binance", "BTC", 0.0001, 0.0001, Instant.now().plusSeconds(3600), Instant.now());
        var shortFunding = new FundingRate("bybit", "BTC", 0.0001, 0.0001, Instant.now().plusSeconds(3600), Instant.now());

        assertTrue(filter.passes(longTick, shortTick, longFunding, shortFunding));
    }

    @Test
    void rejectsWhenLongFundingRateTooHigh() {
        var filter = new com.arbbot.risk.RiskFilter(riskConfig());
        var longTick = new Tick("BTC", "binance", 50000, 50001, 50001, 50000, 0, 0, Instant.now(), true);
        var shortTick = new Tick("BTC", "bybit", 50100, 50101, 50101, 50100, 0, 0, Instant.now(), true);
        var longFunding = new FundingRate("binance", "BTC", 0.002, 0.002, Instant.now().plusSeconds(3600), Instant.now());
        var shortFunding = new FundingRate("bybit", "BTC", 0.0001, 0.0001, Instant.now().plusSeconds(3600), Instant.now());

        assertFalse(filter.passes(longTick, shortTick, longFunding, shortFunding));
    }

    @Test
    void rejectsWhenFundingSettlementImminent() {
        var filter = new com.arbbot.risk.RiskFilter(riskConfig());
        var longTick = new Tick("BTC", "binance", 50000, 50001, 50001, 50000, 0, 0, Instant.now(), true);
        var shortTick = new Tick("BTC", "bybit", 50100, 50101, 50101, 50100, 0, 0, Instant.now(), true);
        // Settlement in 2 minutes < 5 minute buffer
        var longFunding = new FundingRate("binance", "BTC", 0.0001, 0.0001, Instant.now().plusSeconds(120), Instant.now());
        var shortFunding = new FundingRate("bybit", "BTC", 0.0001, 0.0001, Instant.now().plusSeconds(3600), Instant.now());

        assertFalse(filter.passes(longTick, shortTick, longFunding, shortFunding));
    }

    @Test
    void rejectsWhenLongTickUnreliable() {
        var filter = new com.arbbot.risk.RiskFilter(riskConfig());
        var longTick = Tick.unreliable("BTC", "binance");
        var shortTick = new Tick("BTC", "bybit", 50100, 50101, 50101, 50100, 0, 0, Instant.now(), true);
        var funding = new FundingRate("binance", "BTC", 0.0001, 0.0001, Instant.now().plusSeconds(3600), Instant.now());

        assertFalse(filter.passes(longTick, shortTick, funding, funding));
    }
}
