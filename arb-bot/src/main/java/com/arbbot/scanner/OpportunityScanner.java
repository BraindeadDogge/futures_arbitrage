package com.arbbot.scanner;

import com.arbbot.config.AppConfig;
import com.arbbot.fees.FeeEngine;
import com.arbbot.fees.FundingRate;
import com.arbbot.market.OrderBook;
import com.arbbot.market.OrderBookManager;
import com.arbbot.market.Tick;
import com.arbbot.market.SymbolRegistry;
import com.arbbot.risk.RiskFilter;
import com.arbbot.storage.OpportunityStore;
import com.arbbot.storage.OpportunityStore.OpportunitySession;
import com.arbbot.storage.OpportunityStore.OpportunityTick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class OpportunityScanner {

    private static final Logger log = LoggerFactory.getLogger(OpportunityScanner.class);
    private static final double ESTIMATED_HOLDING_HOURS = 4.0;
    /** Consecutive missing ticks before a session is closed (~500 ms at 50 ms scan interval). */
    private static final int SESSION_GAP_TICKS = 10;
    /** Only persist to DB if net% changed by more than this, or SAVE_DEBOUNCE_MS has elapsed. */
    private static final double SAVE_DEBOUNCE_PCT = 0.01;
    private static final long SAVE_DEBOUNCE_MS = 2_000;
    /** Only record a session tick if net% changed by more than this, or TICK_DEBOUNCE_MS has elapsed. */
    private static final double TICK_DEBOUNCE_PCT = 0.01;
    private static final long TICK_DEBOUNCE_MS = 1_000;

    private record TickSnapshot(long recordedAt, double netPct, double grossPct,
                                double maxVolumeUsdt, double longAsk, double shortBid) {}

    private static class ActiveSession {
        final String id = UUID.randomUUID().toString();
        final String symbol, longEx, shortEx;
        final long startedAt = System.currentTimeMillis();
        double peakNet = 0, sumNet = 0, minNet = Double.MAX_VALUE;
        double entryNet = -1, lastNet = 0;
        double peakVolume = 0, sumVolume = 0;
        int tickCount = 0, missingTicks = 0;
        boolean seenThisScan = false;
        final List<TickSnapshot> ticks = new ArrayList<>();
        // Debounce: last DB-saved state
        double lastSavedNetPct = Double.NaN;
        long lastSavedMs = 0;
        // Debounce: last tick recorded into ticks list
        double lastTickNetPct = Double.NaN;
        long lastTickMs = 0;

        ActiveSession(String symbol, String longEx, String shortEx) {
            this.symbol = symbol; this.longEx = longEx; this.shortEx = shortEx;
        }
    }

    private final Map<String, ActiveSession> activeSessions = new ConcurrentHashMap<>();

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
        activeSessions.values().forEach(s -> s.seenThisScan = false);

        for (String canonical : symbolRegistry.getWatchedSymbols()) {
            Map<String, String> exchangeSymbols = symbolRegistry.getExchangeSymbolsFor(canonical);
            List<Tick> ticks = orderBookManager.getAllTicks(canonical, exchangeSymbols, config.orderSizeUsdt());
            List<Tick> reliable = ticks.stream().filter(Tick::isReliable).toList();
            if (reliable.size() < 2) continue;

            for (int i = 0; i < reliable.size(); i++) {
                for (int j = i + 1; j < reliable.size(); j++) {
                    evaluatePair(canonical, reliable.get(i), reliable.get(j), exchangeSymbols);
                    evaluatePair(canonical, reliable.get(j), reliable.get(i), exchangeSymbols);
                }
            }
        }

        closeStaleSessions();
    }

    private void closeStaleSessions() {
        long now = System.currentTimeMillis();
        activeSessions.entrySet().removeIf(entry -> {
            ActiveSession s = entry.getValue();
            if (s.seenThisScan) { s.missingTicks = 0; return false; }
            s.missingTicks++;
            if (s.missingTicks >= SESSION_GAP_TICKS && s.tickCount > 0) {
                double avgNet = s.tickCount > 0 ? s.sumNet / s.tickCount : 0;
                double avgVol = s.tickCount > 0 ? s.sumVolume / s.tickCount : 0;
                store.saveSession(new OpportunitySession(
                    s.id, s.symbol, s.longEx, s.shortEx,
                    Instant.ofEpochMilli(s.startedAt),
                    Instant.ofEpochMilli(now),
                    s.peakNet, avgNet,
                    s.minNet == Double.MAX_VALUE ? 0 : s.minNet,
                    s.entryNet, s.lastNet,
                    s.peakVolume, avgVol,
                    now - s.startedAt,
                    s.tickCount));
                List<OpportunityTick> oppTicks = new ArrayList<>(s.ticks.size());
                for (int i = 0; i < s.ticks.size(); i++) {
                    TickSnapshot t = s.ticks.get(i);
                    oppTicks.add(new OpportunityTick(s.id, i,
                        Instant.ofEpochMilli(t.recordedAt()),
                        t.netPct(), t.grossPct(), t.maxVolumeUsdt(),
                        t.longAsk(), t.shortBid()));
                }
                store.saveSessionTicks(oppTicks);
                return true;
            }
            return false;
        });
    }

    private void evaluatePair(String symbol, Tick buyTick, Tick sellTick,
                               Map<String, String> exchangeSymbols) {
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

        // Compute max profitable volume using depth from Tick fields
        String longSym  = exchangeSymbols.getOrDefault(buyTick.exchange(), "");
        String shortSym = exchangeSymbols.getOrDefault(sellTick.exchange(), "");
        OrderBook longBook  = orderBookManager.getOrCreateBook(buyTick.exchange(), longSym);
        OrderBook shortBook = orderBookManager.getOrCreateBook(sellTick.exchange(), shortSym);
        double maxVol        = SpreadCalculator.maxProfitableVolume(longBook, shortBook, cost);
        // Depth already captured atomically in Tick at the same instant as effective prices
        double longAskDepth  = buyTick.askDepthUsdt();
        double shortBidDepth = sellTick.bidDepthUsdt();

        double netPct = net * 100;
        double grossPct = gross * 100;
        long nowMs = System.currentTimeMillis();

        // Session tracking (always update stats; debounce only controls persistence)
        String sessionKey = symbol + "|" + buyTick.exchange() + "|" + sellTick.exchange();
        ActiveSession session = activeSessions.computeIfAbsent(sessionKey,
            k -> new ActiveSession(symbol, buyTick.exchange(), sellTick.exchange()));
        session.seenThisScan = true;
        session.missingTicks = 0;
        if (session.entryNet < 0) session.entryNet = netPct;
        session.lastNet = netPct;
        session.peakNet = Math.max(session.peakNet, netPct);
        session.minNet  = Math.min(session.minNet, netPct);
        session.sumNet += netPct;
        session.peakVolume = Math.max(session.peakVolume, maxVol);
        session.sumVolume += maxVol;
        session.tickCount++;

        // Debounced tick recording
        boolean tickChanged = Double.isNaN(session.lastTickNetPct)
            || Math.abs(netPct - session.lastTickNetPct) >= TICK_DEBOUNCE_PCT
            || (nowMs - session.lastTickMs) >= TICK_DEBOUNCE_MS;
        if (tickChanged) {
            session.ticks.add(new TickSnapshot(nowMs, netPct, grossPct,
                maxVol, buyTick.effectiveBuyPrice(), sellTick.effectiveSellPrice()));
            session.lastTickNetPct = netPct;
            session.lastTickMs = nowMs;
        }

        // Debounced DB write
        boolean shouldSave = Double.isNaN(session.lastSavedNetPct)
            || Math.abs(netPct - session.lastSavedNetPct) >= SAVE_DEBOUNCE_PCT
            || (nowMs - session.lastSavedMs) >= SAVE_DEBOUNCE_MS;
        if (shouldSave) {
            Opportunity opp = new Opportunity(
                UUID.randomUUID(), symbol,
                buyTick.exchange(), buyTick.effectiveBuyPrice(), buyTick.bestBid(),
                sellTick.exchange(), sellTick.effectiveSellPrice(), sellTick.bestAsk(),
                gross, net, cost,
                longFunding, shortFunding,
                config.orderSizeUsdt(),
                maxVol, longAskDepth, shortBidDepth,
                Instant.now()
            );
            store.save(opp);
            session.lastSavedNetPct = netPct;
            session.lastSavedMs = nowMs;
        }

        log.info("OPPORTUNITY: sym={} long={} short={} gross={}% net={}% maxVol=${}",
            symbol, buyTick.exchange(), sellTick.exchange(),
            String.format("%.4f", grossPct), String.format("%.4f", netPct),
            String.format("%.0f", maxVol));
    }
}
