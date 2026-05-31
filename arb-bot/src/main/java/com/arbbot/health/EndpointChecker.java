package com.arbbot.health;

import com.arbbot.exchange.ExchangeHealth;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EndpointChecker {

  private static final Logger log = LoggerFactory.getLogger(EndpointChecker.class);

  private final String baseUrl;
  private final String pingPath;
  private final OkHttpClient client;

  public EndpointChecker(String baseUrl, String pingPath, long timeoutMs) {
    this.baseUrl = baseUrl;
    this.pingPath = pingPath;
    this.client =
        new OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build();
  }

  public ExchangeHealth check(String exchangeName) {
    long start = System.currentTimeMillis();
    Request request = new Request.Builder().url(baseUrl + pingPath).get().build();
    try (Response response = client.newCall(request).execute()) {
      long latency = System.currentTimeMillis() - start;
      if (response.code() == 200 || response.isSuccessful()) {
        return ExchangeHealth.unknown(exchangeName).withRestAlive(true, latency, Instant.now());
      }
      String body = "";
      try { var b = response.body(); body = b != null ? b.string() : ""; } catch (Exception ignored) {}
      if (body.length() > 200) body = body.substring(0, 200) + "…";
      String err = "HTTP " + response.code();
      log.warn("[{}] Endpoint check failed: {} body={}", exchangeName, err, body.isBlank() ? "(empty)" : body);
      return ExchangeHealth.unknown(exchangeName)
          .withRestAlive(false, latency, Instant.now())
          .withError(err);
    } catch (Exception e) {
      long latency = System.currentTimeMillis() - start;
      log.warn("[{}] Endpoint check error: {}", exchangeName, e.getMessage());
      return ExchangeHealth.unknown(exchangeName)
          .withRestAlive(false, latency, Instant.now())
          .withError(e.getMessage());
    }
  }
}
