package com.arbbot.exchange.htx;

import com.arbbot.fees.ExchangeFeeClient;
import com.arbbot.fees.FeeSchedule;
import com.arbbot.fees.FundingRate;
import com.arbbot.util.HmacSha256;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.TreeMap;

public class HtxFeeClient implements ExchangeFeeClient {

    private static final Logger log = LoggerFactory.getLogger(HtxFeeClient.class);
    private static final String EXCHANGE = "htx";
    private static final String HOST = "api.hbdm.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO_UTC =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final String baseUrl;
    private final String apiKey;
    private final String apiSecret;
    private final OkHttpClient httpClient;

    public HtxFeeClient(String baseUrl, String apiKey, String apiSecret, OkHttpClient httpClient) {
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
            String path = "/linear-swap-api/v1/swap_fee";
            String queryString = buildAuthQueryString("POST", path);
            String url = baseUrl + path + "?" + queryString;
            String bodyJson = "{\"contract_code\":\"" + exchangeSymbol + "\"}";
            RequestBody body = RequestBody.create(bodyJson,
                MediaType.parse("application/json; charset=utf-8"));
            Request request = new Request.Builder().url(url).post(body).build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("[htx] fetchFeeSchedule HTTP {} for {}", response.code(), exchangeSymbol);
                    return Optional.empty();
                }
                JsonNode root = MAPPER.readTree(response.body().string());
                JsonNode data = root.path("data");
                JsonNode item = data.isArray() ? data.get(0) : data;
                if (item == null || item.isMissingNode()) return Optional.empty();
                double taker = Double.parseDouble(item.path("open_taker_fee").asText("0.0004"));
                double maker = Double.parseDouble(item.path("open_maker_fee").asText("-0.0002"));
                return Optional.of(new FeeSchedule(EXCHANGE, canonicalSymbol, maker, taker, Instant.now(), false));
            }
        } catch (Exception e) {
            log.warn("[htx] fetchFeeSchedule error for {}: {}", exchangeSymbol, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<FundingRate> fetchFundingRate(String canonicalSymbol, String exchangeSymbol) {
        try {
            String url = baseUrl + "/linear-swap-api/v1/swap_funding_rate?contract_code=" + exchangeSymbol;
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("[htx] fetchFundingRate HTTP {} for {}", response.code(), exchangeSymbol);
                    return Optional.empty();
                }
                JsonNode root = MAPPER.readTree(response.body().string());
                JsonNode data = root.path("data");
                double rate = Double.parseDouble(data.path("funding_rate").asText("0"));
                // next_funding_time has been null since Jan 2024 — always use Instant.MAX
                return Optional.of(new FundingRate(EXCHANGE, canonicalSymbol, rate, rate, Instant.MAX, Instant.now()));
            }
        } catch (Exception e) {
            log.warn("[htx] fetchFundingRate error for {}: {}", exchangeSymbol, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Builds the HMAC-SHA256 v2 signed query string for HTX authenticated endpoints.
     * Auth params go in the query string for both GET and POST requests.
     */
    private String buildAuthQueryString(String method, String path) throws Exception {
        String timestamp = ISO_UTC.format(Instant.now());

        // Auth params must be sorted alphabetically
        TreeMap<String, String> params = new TreeMap<>();
        params.put("AccessKeyId", apiKey);
        params.put("SignatureMethod", "HmacSHA256");
        params.put("SignatureVersion", "2");
        params.put("Timestamp", timestamp);

        // Build the raw query string (values URL-encoded)
        StringBuilder rawQuery = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (rawQuery.length() > 0) rawQuery.append("&");
            rawQuery.append(entry.getKey()).append("=")
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }

        // String to sign: METHOD\nHOST\nPATH\nQUERY
        String stringToSign = method + "\n" + HOST + "\n" + path + "\n" + rawQuery;
        String signature = HmacSha256.base64(apiSecret, stringToSign);

        return rawQuery + "&Signature=" + URLEncoder.encode(signature, StandardCharsets.UTF_8);
    }
}
