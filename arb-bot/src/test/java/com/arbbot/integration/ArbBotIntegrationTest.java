package com.arbbot.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.arbbot.config.AppConfig;
import com.arbbot.exchange.binance.BinanceWsClient;
import com.arbbot.exchange.bybit.BybitWsClient;
import com.arbbot.fees.*;
import com.arbbot.health.HealthMonitor;
import com.arbbot.market.*;
import com.arbbot.risk.RiskFilter;
import com.arbbot.scanner.OpportunityScanner;
import com.arbbot.storage.OpportunityStore;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

@Tag("integration")
class ArbBotIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ArbBotIntegrationTest.class);

    private Path dbFile;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("integration-test", ".db");
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
    }

    @Test
    void receivesTicksFromBinanceAndBybitIn60Seconds() throws Exception {
        OkHttpClient http = new OkHttpClient();
        HealthMonitor health = new HealthMonitor(5000);
        OrderBookManager obm = new OrderBookManager(5000);

        List<String> symbols = List.of("BTCUSDT");

        var binanceWs = new BinanceWsClient(
            "wss://fstream.binance.com", "https://fapi.binance.com",
            symbols, obm, health, http);
        var bybitWs = new BybitWsClient(
            "wss://stream.bybit.com/v5/public/linear", symbols, obm, health, http);

        binanceWs.connect();
        bybitWs.connect();

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (obm.getOrCreateBook("binance", "BTCUSDT").isInitialized() &&
                obm.getOrCreateBook("bybit", "BTCUSDT").isInitialized()) break;
            Thread.sleep(500);
        }

        assertTrue(obm.getOrCreateBook("binance", "BTCUSDT").isInitialized(),
            "Binance order book not initialized within 30s");
        assertTrue(obm.getOrCreateBook("bybit", "BTCUSDT").isInitialized(),
            "Bybit order book not initialized within 30s");

        var biTick = obm.getTick("binance", "BTCUSDT", "BTC", 1000.0);
        var bbTick = obm.getTick("bybit", "BTCUSDT", "BTC", 1000.0);
        assertTrue(biTick.isPresent() && biTick.get().isReliable(), "Binance tick not reliable");
        assertTrue(bbTick.isPresent() && bbTick.get().isReliable(), "Bybit tick not reliable");

        assertTrue(biTick.get().bestBid() > 10_000, "Binance BTC bid too low: " + biTick.get().bestBid());
        assertTrue(bbTick.get().bestAsk() > 10_000, "Bybit BTC ask too low: " + bbTick.get().bestAsk());

        FeeEngine feeEngine = new FeeEngine();
        feeEngine.updateFeeSchedule("binance", "BTC", FeeSchedule.defaultFor("binance"));
        feeEngine.updateFeeSchedule("bybit", "BTC", FeeSchedule.defaultFor("bybit"));
        feeEngine.updateFundingRate("binance", "BTC", FundingRate.zero("binance", "BTC"));
        feeEngine.updateFundingRate("bybit", "BTC", FundingRate.zero("bybit", "BTC"));

        SymbolRegistry registry = new SymbolRegistry();
        registry.setWatchedSymbols(List.of("BTC"));
        registry.loadExchangeSymbolDirectly("binance", "BTC", "BTCUSDT");
        registry.loadExchangeSymbolDirectly("bybit", "BTC", "BTCUSDT");

        var store = new OpportunityStore(dbFile.toString(), 100);
        store.start();

        var scanner = new OpportunityScanner(obm, registry, feeEngine,
            new RiskFilter(new AppConfig.RiskConfig(0.5, 1)),
            store, new AppConfig.ScannerConfig(0.0, 5.0, 1000.0, 50, List.of("BTC")));

        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        sched.scheduleAtFixedRate(scanner::scan, 0, 50, TimeUnit.MILLISECONDS);
        Thread.sleep(5000);
        sched.shutdown();

        store.flush();
        var stats = store.queryStats();
        log.info("Integration test stats: {} opportunities detected", stats.totalOpportunities());
        assertNotNull(stats);
        assertFalse(stats.totalOpportunities() < 0);

        binanceWs.disconnect();
        bybitWs.disconnect();
        store.close();
    }
}
