package com.arbbot.exchange.gate;

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

public class GateFeeClient implements ExchangeFeeClient {

    private static final Logger log = LoggerFactory.getLogger(GateFeeClient.class);
    private static final String EXCHANGE = "gate";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final OkHttpClient httpClient;

    public GateFeeClient(String baseUrl, OkHttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
    }

    @Override
    public Optional<FeeSchedule> fetchFeeSchedule(String canonicalSymbol, String exchangeSymbol) {
        try {
            JsonNode contract = fetchContract(exchangeSymbol);
            if (contract == null) return Optional.empty();
            double taker = Double.parseDouble(contract.path("taker_fee_rate").asText("0.0005"));
            double maker = Double.parseDouble(contract.path("maker_fee_rate").asText("0.0002"));
            return Optional.of(new FeeSchedule(EXCHANGE, canonicalSymbol, maker, taker, Instant.now(), false));
        } catch (Exception e) {
            log.warn("[gate] fetchFeeSchedule error for {}: {}", exchangeSymbol, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<FundingRate> fetchFundingRate(String canonicalSymbol, String exchangeSymbol) {
        try {
            JsonNode contract = fetchContract(exchangeSymbol);
            if (contract == null) return Optional.empty();
            double rate = Double.parseDouble(contract.path("funding_rate").asText("0"));
            // funding_next_apply is seconds (float) — convert to Instant
            double nextApplySec = contract.path("funding_next_apply").asDouble(0);
            Instant nextSettlement = nextApplySec > 0
                ? Instant.ofEpochSecond((long) nextApplySec)
                : Instant.MAX;
            return Optional.of(new FundingRate(EXCHANGE, canonicalSymbol, rate, rate, nextSettlement, Instant.now()));
        } catch (Exception e) {
            log.warn("[gate] fetchFundingRate error for {}: {}", exchangeSymbol, e.getMessage());
            return Optional.empty();
        }
    }

    private JsonNode fetchContract(String exchangeSymbol) throws Exception {
        String url = baseUrl + "/futures/usdt/contracts/" + exchangeSymbol;
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("[gate] Contract fetch HTTP {} for {}", response.code(), exchangeSymbol);
                return null;
            }
            return MAPPER.readTree(response.body().string());
        }
    }
}
