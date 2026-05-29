package com.arbbot.scanner;

import com.arbbot.fees.FundingRate;
import java.time.Instant;
import java.util.UUID;

public record Opportunity(
    UUID id,
    String canonicalSymbol,
    String longExchange,
    double longExchangeAskPrice,
    double longExchangeBestBid,
    String shortExchange,
    double shortExchangeBidPrice,
    double shortExchangeBestAsk,
    double grossSpreadPct,
    double netSpreadPct,
    double estimatedTotalCostPct,
    FundingRate longExchangeFunding,
    FundingRate shortExchangeFunding,
    double orderSizeUsdt,
    double maxVolumeUsdt,
    double longAskDepthUsdt,
    double shortBidDepthUsdt,
    Instant detectedAt) {}
