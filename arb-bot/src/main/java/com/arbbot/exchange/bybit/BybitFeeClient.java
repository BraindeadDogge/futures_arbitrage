package com.arbbot.exchange.bybit;

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

public class BybitFeeClient implements ExchangeFeeClient {

  private static final Logger log = LoggerFactory.getLogger(BybitFeeClient.class);
  private static final String EXCHANGE = "bybit";
  private static final String RECV_WINDOW = "5000";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String baseUrl;
  private final String apiKey;
  private final String apiSecret;
  private final OkHttpClient httpClient;

  public BybitFeeClient(
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
      String timestamp = String.valueOf(System.currentTimeMillis());
      String queryString = "category=linear&symbol=" + exchangeSymbol;
      // HMAC = HMAC-SHA256(secret, timestamp + apiKey + recvWindow + queryString)
      String signPayload = timestamp + apiKey + RECV_WINDOW + queryString;
      String signature = HmacSha256.hex(apiSecret, signPayload);

      String url = baseUrl + "/v5/account/fee-rate?" + queryString;
      Request request =
          new Request.Builder()
              .url(url)
              .header("X-BAPI-API-KEY", apiKey)
              .header("X-BAPI-SIGN", signature)
              .header("X-BAPI-TIMESTAMP", timestamp)
              .header("X-BAPI-RECV-WINDOW", RECV_WINDOW)
              .get()
              .build();

      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful() || response.body() == null) {
          log.warn("[bybit] fetchFeeSchedule failed for {}: HTTP {}", exchangeSymbol,
              response.code());
          return Optional.empty();
        }
        JsonNode json = MAPPER.readTree(response.body().string());
        JsonNode list = json.path("result").path("list");
        if (!list.isArray() || list.isEmpty()) {
          return Optional.empty();
        }
        JsonNode item = list.get(0);
        double maker = item.get("makerFeeRate").asDouble();
        double taker = item.get("takerFeeRate").asDouble();
        return Optional.of(
            new FeeSchedule(EXCHANGE, canonicalSymbol, maker, taker, Instant.now(), false));
      }
    } catch (Exception e) {
      log.warn("[bybit] fetchFeeSchedule error for {}: {}", exchangeSymbol, e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public Optional<FundingRate> fetchFundingRate(String canonicalSymbol, String exchangeSymbol) {
    try {
      String url = baseUrl + "/v5/market/tickers?category=linear&symbol=" + exchangeSymbol;
      Request request = new Request.Builder().url(url).get().build();

      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful() || response.body() == null) {
          log.warn("[bybit] fetchFundingRate failed for {}: HTTP {}", exchangeSymbol,
              response.code());
          return Optional.empty();
        }
        JsonNode json = MAPPER.readTree(response.body().string());
        JsonNode list = json.path("result").path("list");
        if (!list.isArray() || list.isEmpty()) {
          return Optional.empty();
        }
        JsonNode item = list.get(0);
        double fundingRate = item.get("fundingRate").asDouble();
        long nextFundingTimeMs = item.get("nextFundingTime").asLong();
        Instant nextSettlement = Instant.ofEpochMilli(nextFundingTimeMs);
        return Optional.of(
            new FundingRate(
                EXCHANGE, canonicalSymbol, fundingRate, fundingRate, nextSettlement,
                Instant.now()));
      }
    } catch (Exception e) {
      log.warn("[bybit] fetchFundingRate error for {}: {}", exchangeSymbol, e.getMessage());
      return Optional.empty();
    }
  }
}
