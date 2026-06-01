package com.arbbot.risk;

import com.arbbot.config.AppConfig;
import com.arbbot.fees.FundingRate;
import com.arbbot.market.Tick;

public final class RiskFilter {

    private final AppConfig.RiskConfig config;

    public RiskFilter(AppConfig.RiskConfig config) {
        this.config = config;
    }

    public boolean passes(Tick longTick, Tick shortTick,
                          FundingRate longFunding, FundingRate shortFunding) {
        if (!longTick.isReliable() || !shortTick.isReliable()) return false;
        double maxFunding = config.maxFundingRatePercent() / 100.0;
        if (Math.abs(longFunding.currentRate()) > maxFunding) return false;
        if (Math.abs(shortFunding.currentRate()) > maxFunding) return false;
        if (longFunding.settlesWithin(config.minFundingTimeBufferMinutes())) return false;
        if (shortFunding.settlesWithin(config.minFundingTimeBufferMinutes())) return false;
        return true;
    }
}
