package com.arbbot.exchange.okx;

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

public class OkxFeeClient implements ExchangeFeeClient {

  private static final Logger log = LoggerFactory.getLogger(OkxFeeClient.class);
  private static final String EXCHANGE = "okx";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String baseUrl;
  private final String apiKey;
  private final String apiSecret;
  private final String apiPassphrase;
  private final OkHttpClient httpClient;

  public OkxFeeClient(
      String baseUrl,
      String apiKey,
      String apiSecret,
      String apiPassphrase,
      OkHttpClient httpClient) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.apiSecret = apiSecret;
    this.apiPassphrase = apiPassphrase;
    this.httpClient = httpClient;
  }

  @Override
  public Optional<FeeSchedule> fetchFeeSchedule(String canonicalSymbol, String exchangeSymbol) {
    if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
      return Optional.empty();
    }
    try {
      String path = "/api/v5/account/trade-fee?instType=SWAP&instId=" + exchangeSymbol;
      String timestamp = Instant.now().toString();
      // HMAC = Base64(HMAC-SHA256(secret, timestamp + "GET" + path))
      String signPayload = timestamp + "GET" + path;
      String signature = HmacSha256.base64(apiSecret, signPayload);

      String url = baseUrl + path;
      Request request =
          new Request.Builder()
              .url(url)
              .header("OK-ACCESS-KEY", apiKey)
              .header("OK-ACCESS-SIGN", signature)
              .header("OK-ACCESS-TIMESTAMP", timestamp)
              .header("OK-ACCESS-PASSPHRASE", apiPassphrase)
              .get()
              .build();

      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful() || response.body() == null) {
          log.warn("[okx] fetchFeeSchedule failed for {}: HTTP {}", exchangeSymbol,
              response.code());
          return Optional.empty();
        }
        JsonNode json = MAPPER.readTree(response.body().string());
        JsonNode data = json.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
          return Optional.empty();
        }
        JsonNode item = data.get(0);
        double maker = item.get("makerU").asDouble();
        double taker = item.get("takerU").asDouble();
        return Optional.of(
            new FeeSchedule(EXCHANGE, canonicalSymbol, maker, taker, Instant.now(), false));
      }
    } catch (Exception e) {
      log.warn("[okx] fetchFeeSchedule error for {}: {}", exchangeSymbol, e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public Optional<FundingRate> fetchFundingRate(String canonicalSymbol, String exchangeSymbol) {
    try {
      String url = baseUrl + "/api/v5/public/funding-rate?instId=" + exchangeSymbol;
      Request request = new Request.Builder().url(url).get().build();

      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful() || response.body() == null) {
          log.warn("[okx] fetchFundingRate failed for {}: HTTP {}", exchangeSymbol,
              response.code());
          return Optional.empty();
        }
        JsonNode json = MAPPER.readTree(response.body().string());
        JsonNode data = json.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
          return Optional.empty();
        }
        JsonNode item = data.get(0);
        double fundingRate = item.get("fundingRate").asDouble();
        long nextFundingTimeMs = item.get("nextFundingTime").asLong();
        Instant nextSettlement = Instant.ofEpochMilli(nextFundingTimeMs);
        return Optional.of(
            new FundingRate(
                EXCHANGE, canonicalSymbol, fundingRate, fundingRate, nextSettlement,
                Instant.now()));
      }
    } catch (Exception e) {
      log.warn("[okx] fetchFundingRate error for {}: {}", exchangeSymbol, e.getMessage());
      return Optional.empty();
    }
  }
}
