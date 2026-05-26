package com.arbbot.fees;

import java.util.Optional;

public interface ExchangeFeeClient {

  /** Fetch fee schedule for given symbol. Returns empty if auth unavailable. */
  Optional<FeeSchedule> fetchFeeSchedule(String canonicalSymbol, String exchangeSymbol);

  /** Fetch current + predicted funding rate for given exchange symbol. */
  Optional<FundingRate> fetchFundingRate(String canonicalSymbol, String exchangeSymbol);
}
