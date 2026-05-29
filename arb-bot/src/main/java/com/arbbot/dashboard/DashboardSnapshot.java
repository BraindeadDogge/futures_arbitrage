package com.arbbot.dashboard;

import java.util.List;
import java.util.Map;

public record DashboardSnapshot(
    long serverTimeMs,
    List<ExchangeHealthDto> health,
    List<SymbolSnapshot> prices,
    List<SpreadRow> spreads,
    List<OpportunityDto> recentOpportunities,
    List<SessionDto> recentSessions,
    StatsDto stats) {

  public record ExchangeHealthDto(
      String exchange,
      boolean restAlive,
      boolean wsAlive,
      boolean dataStale,
      long restLatencyMs,
      long lastWsTickAgeMs,
      String lastError) {}

  public record SymbolSnapshot(String symbol, List<ExchangeTick> ticks, List<FundingDto> funding) {}

  public record ExchangeTick(
      String exchange,
      double bestBid,
      double bestAsk,
      double effectiveBuy,
      double effectiveSell,
      boolean reliable,
      long ageMs,
      List<double[]> topBids,
      List<double[]> topAsks,
      double bidDepthUsdt,
      double askDepthUsdt) {}

  public record FundingDto(
      String exchange, String symbol, double currentRate, long nextSettlementMs) {}

  public record SpreadRow(
      String symbol,
      String longExchange,
      String shortExchange,
      double grossSpreadPct,
      double netSpreadPct,
      boolean viable,
      double maxVolumeUsdt) {}

  public record OpportunityDto(
      String id,
      String symbol,
      String longExchange,
      double longAsk,
      double longBestBid,
      String shortExchange,
      double shortBid,
      double shortBestAsk,
      double grossPct,
      double netPct,
      double maxVolumeUsdt,
      double longAskDepthUsdt,
      double shortBidDepthUsdt,
      long detectedAtMs) {}

  public record SessionDto(
      String id,
      String symbol,
      String longExchange,
      String shortExchange,
      long startedAtMs,
      long endedAtMs,
      double peakNetPct,
      double avgNetPct,
      double minNetPct,
      double entryNetPct,
      double exitNetPct,
      double peakVolumeUsdt,
      double avgVolumeUsdt,
      long durationMs,
      int tickCount) {}

  public record StatsDto(
      long totalOpportunities,
      double avgNetSpreadPct,
      double maxNetSpreadPct,
      Map<String, Long> countBySymbol,
      Map<String, Long> countByExchangePair) {}
}
