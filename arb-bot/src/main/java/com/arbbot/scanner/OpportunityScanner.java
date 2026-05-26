package com.arbbot.scanner;

import com.arbbot.config.AppConfig;
import com.arbbot.fees.FeeEngine;
import com.arbbot.fees.FundingRate;
import com.arbbot.market.OrderBookManager;
import com.arbbot.market.Tick;
import com.arbbot.market.SymbolRegistry;
import com.arbbot.risk.RiskFilter;
import com.arbbot.storage.OpportunityStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class OpportunityScanner {

    private static final Logger log = LoggerFactory.getLogger(OpportunityScanner.class);
    private static final double ESTIMATED_HOLDING_HOURS = 4.0;

    private final OrderBookManager orderBookManager;
    private final SymbolRegistry symbolRegistry;
    private final FeeEngine feeEngine;
    private final RiskFilter riskFilter;
    private final OpportunityStore store;
    private final AppConfig.ScannerConfig config;

    public OpportunityScanner(OrderBookManager orderBookManager, SymbolRegistry symbolRegistry,
                               FeeEngine feeEngine, RiskFilter riskFilter,
                               OpportunityStore store, AppConfig.ScannerConfig config) {
        this.orderBookManager = orderBookManager;
        this.symbolRegistry = symbolRegistry;
        this.feeEngine = feeEngine;
        this.riskFilter = riskFilter;
        this.store = store;
        this.config = config;
    }

    public void scan() {
        for (String canonical : symbolRegistry.getWatchedSymbols()) {
            Map<String, String> exchangeSymbols = symbolRegistry.getExchangeSymbolsFor(canonical);
            List<Tick> ticks = orderBookManager.getAllTicks(canonical, exchangeSymbols, config.orderSizeUsdt());
            List<Tick> reliable = ticks.stream().filter(Tick::isReliable).toList();
            if (reliable.size() < 2) continue;

            for (int i = 0; i < reliable.size(); i++) {
                for (int j = i + 1; j < reliable.size(); j++) {
                    evaluatePair(canonical, reliable.get(i), reliable.get(j));
                    evaluatePair(canonical, reliable.get(j), reliable.get(i));
                }
            }
        }
    }

    private void evaluatePair(String symbol, Tick buyTick, Tick sellTick) {
        double gross = SpreadCalculator.grossSpread(buyTick, sellTick);
        if (!Double.isFinite(gross) || gross <= 0) return;

        double maxGross = config.maxGrossSpreadPercent() / 100.0;
        if (gross > maxGross) {
            log.debug("Skipping {} {}->{}: gross spread {}% > sanity cap", symbol,
                buyTick.exchange(), sellTick.exchange(), gross * 100);
            return;
        }

        double net = SpreadCalculator.netSpread(buyTick, sellTick, feeEngine, ESTIMATED_HOLDING_HOURS);
        if (net < config.minNetSpreadPercent() / 100.0) return;

        FundingRate longFunding = feeEngine.getFundingRate(symbol, buyTick.exchange())
            .orElse(FundingRate.zero(buyTick.exchange(), symbol));
        FundingRate shortFunding = feeEngine.getFundingRate(symbol, sellTick.exchange())
            .orElse(FundingRate.zero(sellTick.exchange(), symbol));

        if (!riskFilter.passes(buyTick, sellTick, longFunding, shortFunding)) return;

        double cost = feeEngine.getTotalRoundTripCost(
            symbol, buyTick.exchange(), symbol, sellTick.exchange(), ESTIMATED_HOLDING_HOURS);

        Opportunity opp = new Opportunity(
            UUID.randomUUID(), symbol,
            buyTick.exchange(), buyTick.effectiveBuyPrice(),
            sellTick.exchange(), sellTick.effectiveSellPrice(),
            gross, net, cost,
            longFunding, shortFunding,
            config.orderSizeUsdt(),
            Instant.now()
        );

        store.save(opp);
        log.info("OPPORTUNITY: sym={} long={} short={} gross={}% net={}%",
            symbol, buyTick.exchange(), sellTick.exchange(), gross * 100, net * 100);
    }
}
