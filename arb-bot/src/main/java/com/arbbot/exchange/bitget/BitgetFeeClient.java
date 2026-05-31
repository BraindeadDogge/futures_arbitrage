package com.arbbot.exchange.bitget;

import com.arbbot.fees.ExchangeFeeClient;
import com.arbbot.fees.FeeSchedule;
import com.arbbot.fees.FundingRate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.Optional;

public class BitgetFeeClient implements ExchangeFeeClient {

    private static final Logger log = LoggerFactory.getLogger(BitgetFeeClient.class);
    private static final String EXCHANGE = "bitget";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final OkHttpClient httpClient;

    public BitgetFeeClient(String baseUrl, OkHttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
    }

    @Override
    public Optional<FeeSchedule> fetchFeeSchedule(String canonicalSymbol, String exchangeSymbol) {
        try {
            String url = baseUrl + "/mix/market/contracts?productType=USDT-FUTURES";
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("[bitget] fetchFeeSchedule HTTP {} for {}", response.code(), exchangeSymbol);
                    return Optional.empty();
                }
                JsonNode root = MAPPER.readTree(response.body().string());
                for (JsonNode item : root.path("data")) {
                    if (!exchangeSymbol.equals(item.path("symbol").asText())) continue;
                    double taker = Double.parseDouble(item.path("takerFeeRate").asText("0.0006"));
                    double maker = Double.parseDouble(item.path("makerFeeRate").asText("0.0004"));
                    return Optional.of(new FeeSchedule(EXCHANGE, canonicalSymbol, maker, taker, Instant.now(), false));
                }
                return Optional.empty();
            }
        } catch (Exception e) {
            log.warn("[bitget] fetchFeeSchedule error for {}: {}", exchangeSymbol, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<FundingRate> fetchFundingRate(String canonicalSymbol, String exchangeSymbol) {
        try {
            String url = baseUrl + "/mix/market/current-fund-rate?symbol=" + exchangeSymbol
                + "&productType=USDT-FUTURES";
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("[bitget] fetchFundingRate HTTP {} for {}", response.code(), exchangeSymbol);
                    return Optional.empty();
                }
                JsonNode root = MAPPER.readTree(response.body().string());
                JsonNode data = root.path("data");
                // data is an array; first element contains the rate
                JsonNode item = data.isArray() ? data.get(0) : data;
                if (item == null || item.isMissingNode()) return Optional.empty();
                double rate = Double.parseDouble(item.path("fundingRate").asText("0"));
                // nextUpdate is epoch ms as string
                long nextUpdateMs = item.path("nextUpdate").asLong(0);
                Instant nextSettlement = nextUpdateMs > 0
                    ? Instant.ofEpochMilli(nextUpdateMs)
                    : Instant.MAX;
                return Optional.of(new FundingRate(EXCHANGE, canonicalSymbol, rate, rate, nextSettlement, Instant.now()));
            }
        } catch (Exception e) {
            log.warn("[bitget] fetchFundingRate error for {}: {}", exchangeSymbol, e.getMessage());
            return Optional.empty();
        }
    }
}
