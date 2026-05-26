package com.arbbot.scanner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.arbbot.config.AppConfig;
import com.arbbot.fees.FeeEngine;
import com.arbbot.fees.FeeSchedule;
import com.arbbot.fees.FundingRate;
import com.arbbot.market.OrderBookManager;
import com.arbbot.market.SymbolRegistry;
import com.arbbot.risk.RiskFilter;
import com.arbbot.storage.OpportunityStore;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class OpportunityScannerTest {

    @Test
    void detectsOpportunityWhenNetSpreadAboveThreshold() throws Exception {
        var manager = new OrderBookManager(5000);
        var registry = new SymbolRegistry();
        registry.setWatchedSymbols(List.of("BTC"));

        manager.getOrCreateBook("binance", "BTCUSDT")
            .applySnapshot(Map.of(49999.0, 10.0), Map.of(50000.0, 10.0), 1L);
        manager.getOrCreateBook("bybit", "BTCUSDT")
            .applySnapshot(Map.of(50200.0, 10.0), Map.of(50201.0, 10.0), 1L);

        registry.loadExchangeSymbolDirectly("binance", "BTC", "BTCUSDT");
        registry.loadExchangeSymbolDirectly("bybit", "BTC", "BTCUSDT");

        var feeEngine = new FeeEngine();
        feeEngine.updateFeeSchedule("binance", "BTC",
            new FeeSchedule("binance", "BTC", 0.0002, 0.0004, Instant.now(), false));
        feeEngine.updateFeeSchedule("bybit", "BTC",
            new FeeSchedule("bybit", "BTC", 0.0001, 0.0006, Instant.now(), false));
        feeEngine.updateFundingRate("binance", "BTC", FundingRate.zero("binance", "BTC"));
        feeEngine.updateFundingRate("bybit", "BTC", FundingRate.zero("bybit", "BTC"));

        var scanner_config = new AppConfig.ScannerConfig(0.05, 3.0, 1000.0, 50, List.of("BTC"));
        var risk_config = new AppConfig.RiskConfig(0.1, 5);

        AtomicInteger saved = new AtomicInteger(0);
        var store = mock(OpportunityStore.class);
        doAnswer(inv -> { saved.incrementAndGet(); return null; }).when(store).save(any());

        var scanner = new OpportunityScanner(manager, registry, feeEngine,
            new RiskFilter(risk_config), store, scanner_config);
        scanner.scan();

        assertTrue(saved.get() > 0, "Expected at least one opportunity to be detected");
    }

    @Test
    void doesNotEmitWhenNetSpreadBelowThreshold() throws Exception {
        var manager = new OrderBookManager(5000);
        var registry = new SymbolRegistry();
        registry.setWatchedSymbols(List.of("BTC"));

        manager.getOrCreateBook("binance", "BTCUSDT")
            .applySnapshot(Map.of(49999.0, 10.0), Map.of(50000.0, 10.0), 1L);
        manager.getOrCreateBook("bybit", "BTCUSDT")
            .applySnapshot(Map.of(50050.0, 10.0), Map.of(50051.0, 10.0), 1L);
        registry.loadExchangeSymbolDirectly("binance", "BTC", "BTCUSDT");
        registry.loadExchangeSymbolDirectly("bybit", "BTC", "BTCUSDT");

        var feeEngine = new FeeEngine();
        feeEngine.updateFeeSchedule("binance", "BTC",
            new FeeSchedule("binance", "BTC", 0.0002, 0.0004, Instant.now(), false));
        feeEngine.updateFeeSchedule("bybit", "BTC",
            new FeeSchedule("bybit", "BTC", 0.0001, 0.0006, Instant.now(), false));
        feeEngine.updateFundingRate("binance", "BTC", FundingRate.zero("binance", "BTC"));
        feeEngine.updateFundingRate("bybit", "BTC", FundingRate.zero("bybit", "BTC"));

        var store = mock(OpportunityStore.class);
        var scanner = new OpportunityScanner(manager, registry, feeEngine,
            new RiskFilter(new AppConfig.RiskConfig(0.1, 5)), store,
            new AppConfig.ScannerConfig(0.05, 3.0, 1000.0, 50, List.of("BTC")));
        scanner.scan();

        verify(store, never()).save(any());
    }
}
