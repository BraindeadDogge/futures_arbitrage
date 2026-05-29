package com.arbbot.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.util.List;

public class AppConfig {

    private final Config root;

    public AppConfig() {
        this(ConfigFactory.load());
    }

    public AppConfig(Config config) {
        this.root = config.getConfig("arbbot");
    }

    public ExchangeConfig exchangeConfig(String exchange) {
        Config c = root.getConfig("exchanges." + exchange);
        return new ExchangeConfig(
            c.getBoolean("enabled"),
            c.getString("restBaseUrl"),
            c.getString("wsBaseUrl"),
            c.hasPath("apiKey") ? c.getString("apiKey") : null,
            c.hasPath("apiSecret") ? c.getString("apiSecret") : null,
            c.hasPath("apiPassphrase") ? c.getString("apiPassphrase") : null,
            c.hasPath("feeRefreshIntervalMinutes") ? c.getInt("feeRefreshIntervalMinutes") : 30,
            c.hasPath("fundingRateRefreshIntervalMinutes") ? c.getInt("fundingRateRefreshIntervalMinutes") : 5
        );
    }

    public ScannerConfig scannerConfig() {
        Config c = root.getConfig("scanner");
        return new ScannerConfig(
            c.getDouble("minNetSpreadPercent"),
            c.getDouble("maxGrossSpreadPercent"),
            c.getDouble("orderSizeUsdt"),
            c.getLong("scanIntervalMs"),
            c.getStringList("symbols")
        );
    }

    public RiskConfig riskConfig() {
        Config c = root.getConfig("risk");
        return new RiskConfig(
            c.getDouble("maxFundingRatePercent"),
            c.getLong("minFundingTimeBufferMinutes")
        );
    }

    public HealthConfig healthConfig() {
        Config c = root.getConfig("health");
        return new HealthConfig(
            c.getLong("checkIntervalSeconds"),
            c.getLong("wsStaleThresholdMs"),
            c.getLong("endpointTimeoutMs")
        );
    }

    public StorageConfig storageConfig() {
        Config c = root.getConfig("storage");
        return new StorageConfig(
            c.getString("dbPath"),
            c.getLong("flushIntervalMs")
        );
    }

    public record ExchangeConfig(
        boolean enabled,
        String restBaseUrl,
        String wsBaseUrl,
        String apiKey,
        String apiSecret,
        String apiPassphrase,
        int feeRefreshIntervalMinutes,
        int fundingRateRefreshIntervalMinutes
    ) {}

    public record ScannerConfig(
        double minNetSpreadPercent,
        double maxGrossSpreadPercent,
        double orderSizeUsdt,
        long scanIntervalMs,
        List<String> symbols
    ) {}

    public record RiskConfig(
        double maxFundingRatePercent,
        long minFundingTimeBufferMinutes
    ) {}

    public record HealthConfig(
        long checkIntervalSeconds,
        long wsStaleThresholdMs,
        long endpointTimeoutMs
    ) {}

    public record StorageConfig(
        String dbPath,
        long flushIntervalMs
    ) {}

    public DashboardConfig dashboardConfig() {
        Config c = root.getConfig("dashboard");
        return new DashboardConfig(c.getBoolean("enabled"), c.getInt("port"));
    }

    public record DashboardConfig(boolean enabled, int port) {}
}
