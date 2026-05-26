package com.arbbot.exchange.kucoin;

import com.arbbot.fees.ExchangeFeeClient;
import com.arbbot.fees.FeeSchedule;
import com.arbbot.fees.FundingRate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KuCoinFeeClient implements ExchangeFeeClient {

  private static final Logger log = LoggerFactory.getLogger(KuCoinFeeClient.class);
  private static final String EXCHANGE = "kucoin";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String baseUrl;
  // Credentials are accepted for future authenticated endpoints; not used for fee fetching
  private final String apiKey;
  private final String apiSecret;
  private final OkHttpClient httpClient;

  public KuCoinFeeClient(
      String baseUrl, String apiKey, String apiSecret, OkHttpClient httpClient) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.apiSecret = apiSecret;
    this.httpClient = httpClient;
  }

  // KuCoin fees come from a public endpoint (/api/v1/contracts/active) — no auth guard needed
  // unlike other exchange clients that require credentials before fetching.
  @Override
  public Optional<FeeSchedule> fetchFeeSchedule(String canonicalSymbol, String exchangeSymbol) {
    try {
      String url = baseUrl + "/api/v1/contracts/active";
      Request request = new Request.Builder().url(url).get().build();

      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful() || response.body() == null) {
          log.warn("[kucoin] fetchFeeSchedule failed: HTTP {}", response.code());
          return Optional.empty();
        }
        JsonNode json = MAPPER.readTree(response.body().string());
        JsonNode data = json.get("data");
        if (data == null || !data.isArray()) {
          return Optional.empty();
        }
        for (JsonNode contract : data) {
          JsonNode symbolNode = contract.get("symbol");
          if (symbolNode != null && exchangeSymbol.equals(symbolNode.asText())) {
            double maker = contract.get("makerFeeRate").asDouble();
            double taker = contract.get("takerFeeRate").asDouble();
            return Optional.of(
                new FeeSchedule(EXCHANGE, canonicalSymbol, maker, taker, Instant.now(), false));
          }
        }
        log.warn("[kucoin] Symbol {} not found in contracts/active", exchangeSymbol);
        return Optional.empty();
      }
    } catch (Exception e) {
      log.warn("[kucoin] fetchFeeSchedule error for {}: {}", exchangeSymbol, e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public Optional<FundingRate> fetchFundingRate(String canonicalSymbol, String exchangeSymbol) {
    try {
      String url = baseUrl + "/api/v1/funding-rate/" + exchangeSymbol + "/current";
      Request request = new Request.Builder().url(url).get().build();

      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful() || response.body() == null) {
          log.warn("[kucoin] fetchFundingRate failed for {}: HTTP {}", exchangeSymbol,
              response.code());
          return Optional.empty();
        }
        JsonNode json = MAPPER.readTree(response.body().string());
        JsonNode data = json.get("data");
        if (data == null) {
          return Optional.empty();
        }
        double currentRate = data.get("value").asDouble();
        // KuCoin funding rate next settlement not directly in this endpoint; use MAX as sentinel
        return Optional.of(
            new FundingRate(
                EXCHANGE, canonicalSymbol, currentRate, currentRate, Instant.MAX, Instant.now()));
      }
    } catch (Exception e) {
      log.warn("[kucoin] fetchFundingRate error for {}: {}", exchangeSymbol, e.getMessage());
      return Optional.empty();
    }
  }
}
