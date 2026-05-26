package com.arbbot.scanner;

import com.arbbot.fees.FundingRate;
import java.time.Instant;
import java.util.UUID;

public record Opportunity(
    UUID id,
    String canonicalSymbol,
    String longExchange,
    double longExchangeAskPrice,
    String shortExchange,
    double shortExchangeBidPrice,
    double grossSpreadPct,
    double netSpreadPct,
    double estimatedTotalCostPct,
    FundingRate longExchangeFunding,
    FundingRate shortExchangeFunding,
    double orderSizeUsdt,
    Instant detectedAt) {}
