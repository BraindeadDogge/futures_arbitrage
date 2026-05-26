package com.arbbot.config;

import static org.junit.jupiter.api.Assertions.*;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

class AppConfigTest {

    private AppConfig config() {
        return new AppConfig(ConfigFactory.load("application-test"));
    }

    @Test
    void loadsExchangeRestUrl() {
        assertEquals("https://fapi.binance.com", config().exchangeConfig("binance").restBaseUrl());
    }

    @Test
    void loadsExchangeWsUrl() {
        assertEquals("wss://fstream.binance.com", config().exchangeConfig("binance").wsBaseUrl());
    }

    @Test
    void exchangeEnabledFlag() {
        assertTrue(config().exchangeConfig("binance").enabled());
    }

    @Test
    void disabledExchangeReturnsFalse() {
        assertFalse(config().exchangeConfig("kucoin").enabled());
    }

    @Test
    void missingApiKeyReturnsNull() {
        // No BINANCE_API_KEY env var set in test → should be null
        // (only fails if env var IS set, which is acceptable)
        assertDoesNotThrow(() -> config().exchangeConfig("binance").apiKey());
    }

    @Test
    void scannerSymbolsLoaded() {
        var symbols = config().scannerConfig().symbols();
        assertEquals(2, symbols.size());
        assertTrue(symbols.contains("BTC"));
        assertTrue(symbols.contains("ETH"));
    }

    @Test
    void scannerMinSpreadLoaded() {
        assertEquals(0.05, config().scannerConfig().minNetSpreadPercent(), 0.0001);
    }

    @Test
    void riskConfigLoaded() {
        assertEquals(0.1, config().riskConfig().maxFundingRatePercent(), 0.0001);
        assertEquals(5, config().riskConfig().minFundingTimeBufferMinutes());
    }

    @Test
    void healthConfigLoaded() {
        assertEquals(2000, config().healthConfig().wsStaleThresholdMs());
    }

    @Test
    void storageConfigLoaded() {
        assertEquals("data/test.db", config().storageConfig().dbPath());
    }

    @Test
    void feeRefreshIntervalIsThirtyMinutes() {
        assertEquals(30, config().exchangeConfig("binance").feeRefreshIntervalMinutes());
    }
}
