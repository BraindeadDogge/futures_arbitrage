package com.arbbot.dashboard;

import com.arbbot.config.AppConfig;
import com.arbbot.dashboard.DashboardSnapshot.*;
import com.arbbot.exchange.ExchangeHealth;
import com.arbbot.fees.FeeEngine;
import com.arbbot.fees.FundingRate;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.OrderBook;
import com.arbbot.market.OrderBook.PriceLevel;
import com.arbbot.market.OrderBookManager;
import com.arbbot.market.SymbolRegistry;
import com.arbbot.market.Tick;
import com.arbbot.scanner.Opportunity;
import com.arbbot.scanner.SpreadCalculator;
import com.arbbot.storage.OpportunityStore;
import com.arbbot.storage.OpportunityStore.OpportunityStats;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SnapshotAssembler {

  private static final Logger log = LoggerFactory.getLogger(SnapshotAssembler.class);
  private static final int TOP_LEVELS = 10;
  private static final int RECENT_LIMIT = 20;
  private static final double HOLDING_HOURS = 4.0;

  private final OrderBookManager obManager;
  private final SymbolRegistry symbolRegistry;
  private final FeeEngine feeEngine;
  private final HealthMonitor healthMonitor;
  private final OpportunityStore store;
  private final AppConfig.ScannerConfig scanConfig;
  private final List<String> enabledExchanges;

  public SnapshotAssembler(
      OrderBookManager obManager,
      SymbolRegistry symbolRegistry,
      FeeEngine feeEngine,
      HealthMonitor healthMonitor,
      OpportunityStore store,
      AppConfig.ScannerConfig scanConfig,
      List<String> enabledExchanges) {
    this.obManager = obManager;
    this.symbolRegistry = symbolRegistry;
    this.feeEngine = feeEngine;
    this.healthMonitor = healthMonitor;
    this.store = store;
    this.scanConfig = scanConfig;
    this.enabledExchanges = enabledExchanges;
  }

  public DashboardSnapshot buildSnapshot() {
    long now = System.currentTimeMillis();

    // Health
    List<ExchangeHealthDto> health = new ArrayList<>();
    for (String ex : enabledExchanges) {
      ExchangeHealth h = healthMonitor.getHealth(ex);
      long wsAge =
          h.lastWsTick().equals(Instant.EPOCH) ? -1 : now - h.lastWsTick().toEpochMilli();
      health.add(
          new ExchangeHealthDto(
              ex,
              h.restAlive(),
              h.wsAlive(),
              h.dataStale(),
              h.restLatencyMs(),
              wsAge,
              h.lastError()));
    }

    // Prices + depth + funding
    List<SymbolSnapshot> prices = new ArrayList<>();
    List<SpreadRow> spreads = new ArrayList<>();

    for (String canonical : symbolRegistry.getWatchedSymbols()) {
      Map<String, String> exSymbols = symbolRegistry.getExchangeSymbolsFor(canonical);
      List<Tick> ticks =
          obManager.getAllTicks(canonical, exSymbols, scanConfig.orderSizeUsdt());

      List<ExchangeTick> exchangeTicks = new ArrayList<>();
      for (Tick t : ticks) {
        String exSym = exSymbols.getOrDefault(t.exchange(), "");
        OrderBook book = obManager.getOrCreateBook(t.exchange(), exSym);
        List<double[]> topBids = toArray(book.topBids(TOP_LEVELS));
        List<double[]> topAsks = toArray(book.topAsks(TOP_LEVELS));
        long ageMs = now - t.timestamp().toEpochMilli();
        exchangeTicks.add(
            new ExchangeTick(
                t.exchange(),
                t.bestBid(),
                t.bestAsk(),
                t.effectiveBuyPrice(),
                t.effectiveSellPrice(),
                t.isReliable(),
                ageMs,
                topBids,
                topAsks));
      }

      // Funding
      List<FundingDto> funding = new ArrayList<>();
      for (String ex : enabledExchanges) {
        feeEngine
            .getFundingRate(canonical, ex)
            .ifPresent(
                fr -> {
                  long settlMs =
                      fr.nextSettlement().equals(Instant.MAX)
                          ? -1
                          : fr.nextSettlement().toEpochMilli();
                  funding.add(new FundingDto(ex, canonical, fr.currentRate(), settlMs));
                });
      }

      prices.add(new SymbolSnapshot(canonical, exchangeTicks, funding));

      // Spreads — all reliable pairs
      List<Tick> reliable = ticks.stream().filter(Tick::isReliable).toList();
      for (int i = 0; i < reliable.size(); i++) {
        for (int j = i + 1; j < reliable.size(); j++) {
          addSpreadRow(spreads, canonical, reliable.get(i), reliable.get(j));
          addSpreadRow(spreads, canonical, reliable.get(j), reliable.get(i));
        }
      }
    }

    // Sort spreads by netSpreadPct descending
    spreads.sort((a, b) -> Double.compare(b.netSpreadPct(), a.netSpreadPct()));

    // Recent opportunities
    List<OpportunityDto> recentOpps = new ArrayList<>();
    try {
      for (Opportunity o : store.queryRecent(RECENT_LIMIT)) {
        recentOpps.add(
            new OpportunityDto(
                o.id().toString(),
                o.canonicalSymbol(),
                o.longExchange(),
                o.longExchangeAskPrice(),
                o.shortExchange(),
                o.shortExchangeBidPrice(),
                o.grossSpreadPct(),
                o.netSpreadPct(),
                o.detectedAt().toEpochMilli()));
      }
    } catch (Exception e) {
      log.debug("queryRecent failed: {}", e.getMessage());
    }

    // Stats
    StatsDto stats = new StatsDto(0, 0, 0, Map.of(), Map.of());
    try {
      OpportunityStats s = store.queryStats();
      stats =
          new StatsDto(
              s.totalOpportunities(),
              s.avgNetSpreadPct(),
              s.maxNetSpreadPct(),
              s.countBySymbol(),
              s.countByExchangePair());
    } catch (Exception e) {
      log.debug("queryStats failed: {}", e.getMessage());
    }

    return new DashboardSnapshot(now, health, prices, spreads, recentOpps, stats);
  }

  private void addSpreadRow(List<SpreadRow> out, String symbol, Tick buy, Tick sell) {
    double gross = SpreadCalculator.grossSpread(buy, sell);
    if (!Double.isFinite(gross)) return;
    double net = SpreadCalculator.netSpread(buy, sell, feeEngine, HOLDING_HOURS);
    if (!Double.isFinite(net)) return;
    boolean viable = net >= scanConfig.minNetSpreadPercent() / 100.0;
    out.add(new SpreadRow(symbol, buy.exchange(), sell.exchange(), gross * 100, net * 100, viable));
  }

  private static List<double[]> toArray(List<PriceLevel> levels) {
    List<double[]> result = new ArrayList<>(levels.size());
    for (PriceLevel l : levels) result.add(new double[] {l.price(), l.qty()});
    return result;
  }
}
