package com.arbbot.risk;

import com.arbbot.config.AppConfig;
import com.arbbot.fees.FundingRate;
import com.arbbot.market.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RiskFilter {

    private static final Logger log = LoggerFactory.getLogger(RiskFilter.class);

    private final AppConfig.RiskConfig config;

    public RiskFilter(AppConfig.RiskConfig config) {
        this.config = config;
    }

    public boolean passes(Tick longTick, Tick shortTick,
                          FundingRate longFunding, FundingRate shortFunding) {
        if (!longTick.isReliable()) {
            log.debug("Rejected: long feed unreliable for {}/{}", longTick.canonicalSymbol(), longTick.exchange());
            return false;
        }
        if (!shortTick.isReliable()) {
            log.debug("Rejected: short feed unreliable for {}/{}", shortTick.canonicalSymbol(), shortTick.exchange());
            return false;
        }
        double maxFunding = config.maxFundingRatePercent() / 100.0;
        if (Math.abs(longFunding.currentRate()) > maxFunding) {
            log.debug("Rejected: long funding rate {} > threshold {}", longFunding.currentRate(), maxFunding);
            return false;
        }
        if (Math.abs(shortFunding.currentRate()) > maxFunding) {
            log.debug("Rejected: short funding rate {} > threshold {}", shortFunding.currentRate(), maxFunding);
            return false;
        }
        if (longFunding.settlesWithin(config.minFundingTimeBufferMinutes())) {
            log.debug("Rejected: long funding settles too soon");
            return false;
        }
        if (shortFunding.settlesWithin(config.minFundingTimeBufferMinutes())) {
            log.debug("Rejected: short funding settles too soon");
            return false;
        }
        return true;
    }
}
