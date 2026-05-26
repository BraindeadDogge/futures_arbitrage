package com.arbbot.exchange.binance;

import com.arbbot.fees.ExchangeFeeClient;
import com.arbbot.fees.FeeSchedule;
import com.arbbot.fees.FundingRate;
import com.arbbot.util.HmacSha256;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BinanceFeeClient implements ExchangeFeeClient {

  private static final Logger log = LoggerFactory.getLogger(BinanceFeeClient.class);
  private static final String EXCHANGE = "binance";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String baseUrl;
  private final String apiKey;
  private final String apiSecret;
  private final OkHttpClient httpClient;

  public BinanceFeeClient(
      String baseUrl, String apiKey, String apiSecret, OkHttpClient httpClient) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.apiSecret = apiSecret;
    this.httpClient = httpClient;
  }

  @Override
  public Optional<FeeSchedule> fetchFeeSchedule(String canonicalSymbol, String exchangeSymbol) {
    if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
      return Optional.empty();
    }
    try {
      long timestamp = System.currentTimeMillis();
      String queryString = "symbol=" + exchangeSymbol + "&timestamp=" + timestamp;
      String signature = HmacSha256.hex(apiSecret, queryString);
      String url =
          baseUrl + "/fapi/v1/commissionRate?" + queryString + "&signature=" + signature;

      Request request =
          new Request.Builder()
              .url(url)
              .header("X-MBX-APIKEY", apiKey)
              .get()
              .build();

      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful() || response.body() == null) {
          log.warn("[binance] fetchFeeSchedule failed for {}: HTTP {}", exchangeSymbol,
              response.code());
          return Optional.empty();
        }
        JsonNode json = MAPPER.readTree(response.body().string());
        double maker = json.get("makerCommissionRate").asDouble();
        double taker = json.get("takerCommissionRate").asDouble();
        return Optional.of(
            new FeeSchedule(EXCHANGE, canonicalSymbol, maker, taker, Instant.now(), false));
      }
    } catch (Exception e) {
      log.warn("[binance] fetchFeeSchedule error for {}: {}", exchangeSymbol, e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public Optional<FundingRate> fetchFundingRate(String canonicalSymbol, String exchangeSymbol) {
    try {
      String url = baseUrl + "/fapi/v1/premiumIndex?symbol=" + exchangeSymbol;
      Request request = new Request.Builder().url(url).get().build();

      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful() || response.body() == null) {
          log.warn("[binance] fetchFundingRate failed for {}: HTTP {}", exchangeSymbol,
              response.code());
          return Optional.empty();
        }
        JsonNode json = MAPPER.readTree(response.body().string());
        double currentRate = json.get("lastFundingRate").asDouble();
        long nextFundingTimeMs = json.get("nextFundingTime").asLong();
        Instant nextSettlement = Instant.ofEpochMilli(nextFundingTimeMs);
        return Optional.of(
            new FundingRate(
                EXCHANGE, canonicalSymbol, currentRate, currentRate, nextSettlement,
                Instant.now()));
      }
    } catch (Exception e) {
      log.warn("[binance] fetchFundingRate error for {}: {}", exchangeSymbol, e.getMessage());
      return Optional.empty();
    }
  }
}
