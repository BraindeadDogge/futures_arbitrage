package com.arbbot;

import com.arbbot.config.AppConfig;
import com.arbbot.dashboard.DashboardServer;
import com.arbbot.dashboard.SnapshotAssembler;
import com.arbbot.dashboard.SystemStatsCollector;
import com.arbbot.exchange.binance.BinanceFeeClient;
import com.arbbot.exchange.binance.BinanceWsClient;
import com.arbbot.exchange.bitget.BitgetFeeClient;
import com.arbbot.exchange.bitget.BitgetWsClient;
import com.arbbot.exchange.bybit.BybitFeeClient;
import com.arbbot.exchange.bybit.BybitWsClient;
import com.arbbot.exchange.gate.GateFeeClient;
import com.arbbot.exchange.gate.GateWsClient;
import com.arbbot.exchange.htx.HtxFeeClient;
import com.arbbot.exchange.htx.HtxWsClient;
import com.arbbot.exchange.kucoin.KuCoinFeeClient;
import com.arbbot.exchange.kucoin.KuCoinWsClient;
import com.arbbot.exchange.okx.OkxFeeClient;
import com.arbbot.exchange.okx.OkxWsClient;
import com.arbbot.fees.FeeEngine;
import com.arbbot.health.EndpointChecker;
import com.arbbot.health.HealthMonitor;
import com.arbbot.health.MetricsRegistry;
import com.arbbot.market.OrderBookManager;
import com.arbbot.market.SymbolRegistry;
import com.arbbot.risk.RiskFilter;
import com.arbbot.scanner.OpportunityScanner;
import com.arbbot.storage.OpportunityStore;
import com.arbbot.util.ClockSync;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        log.info("=== ARB BOT STARTING ===");

        // 1. Load config
        AppConfig config = new AppConfig();
        var scanConfig = config.scannerConfig();
        var healthConfig = config.healthConfig();

        // 2. Shared HTTP client and clock sync
        OkHttpClient httpClient = new OkHttpClient();
        ClockSync clockSync = new ClockSync(httpClient);

        // 3. Health monitor and metrics
        HealthMonitor healthMonitor = new HealthMonitor(healthConfig.wsStaleThresholdMs());
        MetricsRegistry metrics = new MetricsRegistry(new SimpleMeterRegistry());

        // 4. Endpoint checks — only proceed with live exchanges
        log.info("Checking exchange endpoints...");
        List<String> enabledExchanges = new ArrayList<>();
        for (String ex : List.of("binance", "kucoin", "bybit", "okx", "gate", "bitget", "htx")) {
            AppConfig.ExchangeConfig exCfg = config.exchangeConfig(ex);
            if (!exCfg.enabled()) continue;
            EndpointChecker checker = new EndpointChecker(
                exCfg.restBaseUrl(), pingPathFor(ex), healthConfig.endpointTimeoutMs());
            var health = checker.check(ex);
            healthMonitor.updateRestHealth(ex, health);
            metrics.updateExchangeHealth(ex, health.restAlive(), false);
            if (health.restAlive()) {
                enabledExchanges.add(ex);
                clockSync.syncExchange(ex, exCfg.restBaseUrl() + timePathFor(ex), timeFieldFor(ex));
                metrics.updateClockOffset(ex, clockSync.getOffsetMs(ex));
            } else {
                log.warn("[{}] REST endpoint down — skipping this exchange", ex);
            }
        }

        // 5. Symbol registry
        SymbolRegistry symbolRegistry = new SymbolRegistry(httpClient);
        symbolRegistry.setWatchedSymbols(scanConfig.symbols());
        for (String ex : enabledExchanges) {
            AppConfig.ExchangeConfig exCfg = config.exchangeConfig(ex);
            symbolRegistry.loadExchange(ex, exCfg.restBaseUrl() + symbolPathFor(ex),
                SymbolRegistry.ExchangeFormat.valueOf(ex.toUpperCase().replace("-", "_")));
        }

        // 6. Fee engine
        FeeEngine feeEngine = new FeeEngine();
        for (String ex : enabledExchanges) {
            AppConfig.ExchangeConfig exCfg = config.exchangeConfig(ex);
            var feeClient = buildFeeClient(ex, exCfg, httpClient);
            for (String canonical : scanConfig.symbols()) {
                symbolRegistry.exchangeSymbol(canonical, ex).ifPresent(exSym -> {
                    feeClient.fetchFeeSchedule(canonical, exSym)
                        .ifPresentOrElse(
                            s -> feeEngine.updateFeeSchedule(ex, canonical, s),
                            () -> feeEngine.updateFeeSchedule(ex, canonical,
                                com.arbbot.fees.FeeSchedule.defaultFor(ex)));
                    feeClient.fetchFundingRate(canonical, exSym)
                        .ifPresentOrElse(
                            r -> feeEngine.updateFundingRate(ex, canonical, r),
                            () -> feeEngine.updateFundingRate(ex, canonical,
                                com.arbbot.fees.FundingRate.zero(ex, canonical)));
                });
            }
        }

        // 7. Order book manager
        OrderBookManager obManager = new OrderBookManager(healthConfig.wsStaleThresholdMs());

        // 8. Start WebSocket clients
        List<com.arbbot.exchange.Exchange> wsClients = new ArrayList<>();
        for (String ex : enabledExchanges) {
            AppConfig.ExchangeConfig exCfg = config.exchangeConfig(ex);
            var client = buildWsClient(ex, exCfg, scanConfig.symbols(), symbolRegistry, obManager, healthMonitor, httpClient);
            if (client != null) {
                client.connect();
                wsClients.add(client);
            }
        }

        // 9. Wait for at least one snapshot per exchange (max 30s)
        log.info("Waiting for order book snapshots...");
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            boolean allReady = enabledExchanges.stream().allMatch(ex ->
                scanConfig.symbols().stream()
                    .flatMap(s -> symbolRegistry.exchangeSymbol(s, ex).stream())
                    .anyMatch(sym -> obManager.getOrCreateBook(ex, sym).isInitialized()));
            if (allReady) break;
            Thread.sleep(500);
        }

        // 10. Storage
        OpportunityStore store = new OpportunityStore(
            config.storageConfig().dbPath(), config.storageConfig().flushIntervalMs());
        store.start();

        // 11. Scanner
        RiskFilter riskFilter = new RiskFilter(config.riskConfig());
        OpportunityScanner scanner = new OpportunityScanner(
            obManager, symbolRegistry, feeEngine, riskFilter, store, scanConfig);

        ScheduledExecutorService scanScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("scanner").factory());
        scanScheduler.scheduleAtFixedRate(
            () -> metrics.scanTimer().record(scanner::scan),
            0, scanConfig.scanIntervalMs(), TimeUnit.MILLISECONDS);

        // 11b. Dashboard
        var dashCfg = config.dashboardConfig();
        DashboardServer dashServer = null;
        SystemStatsCollector statsCollector = null;
        if (dashCfg.enabled()) {
            statsCollector = new SystemStatsCollector();
            statsCollector.start();
            var assembler = new SnapshotAssembler(
                obManager, symbolRegistry, feeEngine, healthMonitor, store,
                scanConfig, List.copyOf(enabledExchanges), statsCollector);
            dashServer = new DashboardServer(dashCfg.port(), assembler);
            dashServer.start();
            log.info("Dashboard: http://localhost:{}/", dashCfg.port());
        }
        final DashboardServer dashServerRef = dashServer;
        final SystemStatsCollector statsCollectorRef = statsCollector;

        // 12. Health check scheduler
        ScheduledExecutorService healthScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("health").factory());
        healthScheduler.scheduleAtFixedRate(
            healthMonitor::checkStaleness, 0, healthConfig.checkIntervalSeconds(), TimeUnit.SECONDS);

        log.info("=== ARB BOT STARTED ===");
        log.info("Exchanges: {}", enabledExchanges);
        log.info("Symbols: {}", scanConfig.symbols());
        log.info("Scanning every {}ms | Min net spread: {}%",
            scanConfig.scanIntervalMs(), scanConfig.minNetSpreadPercent());

        // 13. Shutdown hook
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().name("shutdown").unstarted(() -> {
            log.info("Shutting down...");
            if (statsCollectorRef != null) statsCollectorRef.stop();
            if (dashServerRef != null) dashServerRef.stop();
            scanScheduler.shutdown();
            healthScheduler.shutdown();
            wsClients.forEach(com.arbbot.exchange.Exchange::disconnect);
            try {
                var stats = store.queryStats();
                log.info("Final stats: {} opportunities logged, max net spread: {}%",
                    stats.totalOpportunities(), stats.maxNetSpreadPct());
            } catch (Exception e) { log.error("Stats query error on shutdown", e); }
            store.close();
            log.info("Shutdown complete.");
        }));
    }

    private static String pingPathFor(String exchange) {
        return switch (exchange) {
            case "binance" -> "/fapi/v1/ping";
            case "kucoin" -> "/api/v1/timestamp";
            case "bybit" -> "/v5/market/time";
            case "okx" -> "/api/v5/public/time";
            case "gate" -> "/futures/usdt/contracts?limit=1";
            case "bitget" -> "/mix/market/contracts?productType=USDT-FUTURES";
            case "htx" -> "/linear-swap-api/v1/swap_contract_info";
            default -> "/";
        };
    }

    private static String timePathFor(String exchange) {
        return switch (exchange) {
            case "binance" -> "/fapi/v1/time";
            case "kucoin" -> "/api/v1/timestamp";
            case "bybit" -> "/v5/market/time";
            case "okx" -> "/api/v5/public/time";
            // Gate.io futures has no dedicated time endpoint; use spot API time
            case "gate" -> "/spot/time";
            case "bitget" -> "/public/time";
            case "htx" -> "/api/v1/timestamp";
            default -> "/";
        };
    }

    private static String timeFieldFor(String exchange) {
        return switch (exchange) {
            case "binance" -> "serverTime";
            case "kucoin" -> "data";
            case "bybit" -> "time";
            case "okx" -> "/data/0/ts";
            case "gate" -> "server_time";
            case "bitget" -> "/data/serverTime";
            case "htx" -> "ts";
            default -> "time";
        };
    }

    private static String symbolPathFor(String exchange) {
        return switch (exchange) {
            case "binance" -> "/fapi/v1/exchangeInfo";
            case "kucoin" -> "/api/v1/contracts/active";
            case "bybit" -> "/v5/market/instruments-info?category=linear&limit=1000";
            case "okx" -> "/api/v5/public/instruments?instType=SWAP";
            case "gate" -> "/futures/usdt/contracts";
            case "bitget" -> "/mix/market/contracts?productType=USDT-FUTURES";
            case "htx" -> "/linear-swap-api/v1/swap_contract_info";
            default -> "/";
        };
    }

    private static com.arbbot.fees.ExchangeFeeClient buildFeeClient(
            String ex, AppConfig.ExchangeConfig cfg, OkHttpClient http) {
        return switch (ex) {
            case "binance" -> new BinanceFeeClient(cfg.restBaseUrl(), cfg.apiKey(), cfg.apiSecret(), http);
            case "kucoin" -> new KuCoinFeeClient(cfg.restBaseUrl(), cfg.apiKey(), cfg.apiSecret(), http);
            case "bybit" -> new BybitFeeClient(cfg.restBaseUrl(), cfg.apiKey(), cfg.apiSecret(), http);
            case "okx" -> new OkxFeeClient(cfg.restBaseUrl(), cfg.apiKey(), cfg.apiSecret(), cfg.apiPassphrase(), http);
            case "gate" -> new GateFeeClient(cfg.restBaseUrl(), http);
            case "bitget" -> new BitgetFeeClient(cfg.restBaseUrl(), http);
            case "htx" -> new HtxFeeClient(cfg.restBaseUrl(), cfg.apiKey(), cfg.apiSecret(), http);
            default -> throw new IllegalArgumentException("Unknown exchange: " + ex);
        };
    }

    private static com.arbbot.exchange.Exchange buildWsClient(
            String ex, AppConfig.ExchangeConfig cfg, List<String> canonicals,
            SymbolRegistry symbolRegistry, OrderBookManager obm,
            HealthMonitor hm, OkHttpClient http) {
        // Build list of exchange-specific symbols for exchanges that need List<String>
        List<String> exchangeSymbols = canonicals.stream()
            .flatMap(c -> symbolRegistry.exchangeSymbol(c, ex).stream())
            .toList();
        if (exchangeSymbols.isEmpty()) return null;

        // Build canonical→exchangeSymbol map for exchanges that need Map<String, String>
        Map<String, String> canonicalToExSym = new LinkedHashMap<>();
        for (String c : canonicals) {
            symbolRegistry.exchangeSymbol(c, ex).ifPresent(s -> canonicalToExSym.put(c, s));
        }

        return switch (ex) {
            case "binance" -> new BinanceWsClient(cfg.wsBaseUrl(), cfg.restBaseUrl(), exchangeSymbols, obm, hm, http);
            case "kucoin" -> new KuCoinWsClient(cfg.restBaseUrl(), canonicalToExSym, obm, hm, http);
            case "bybit" -> new BybitWsClient(cfg.wsBaseUrl(), exchangeSymbols, obm, hm, http);
            case "okx" -> new OkxWsClient(cfg.wsBaseUrl(), canonicalToExSym, obm, hm, http);
            case "gate" -> new GateWsClient(cfg.wsBaseUrl(), canonicalToExSym, obm, hm, http);
            case "bitget" -> new BitgetWsClient(cfg.wsBaseUrl(), exchangeSymbols, obm, hm, http);
            case "htx" -> new HtxWsClient(cfg.wsBaseUrl(), exchangeSymbols, obm, hm, http);
            default -> throw new IllegalArgumentException("Unknown exchange: " + ex);
        };
    }
}
