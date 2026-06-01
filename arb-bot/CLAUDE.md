# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run (dev)
./gradlew run

# Run with fixed log/DB paths (required for IntelliJ run configs)
./gradlew run -Darbbot.log.dir=$(pwd)/logs -Darbbot.storage.dbPath=$(pwd)/data/opportunities.db

# Test (unit only — integration tests excluded by default)
./gradlew test

# Single test class
./gradlew test --tests "com.arbbot.storage.OpportunityStoreTest"

# Integration tests (require live internet + exchange connectivity)
./gradlew test -Dtest.tags=integration

# Build fat JAR
./gradlew shadowJar   # → build/libs/arb-bot.jar

# Format
./gradlew spotlessApply    # auto-fix
./gradlew spotlessCheck    # check only
./gradlew checkstyle       # Checkstyle
```

`--enable-preview` is applied automatically for compile, test, and run tasks via `build.gradle.kts`.

**Never commit** anything under `docs/` — plans, brainstorms, specs are local-only (it's in `.gitignore` via the docs path, but the rule is firm regardless). The `docs/` folder contains `superpowers/plans/` and `superpowers/specs/` which must stay local.

## Architecture

This is a Java 21 cross-exchange futures arbitrage **scanner** (Phase 1 — detection only, no order placement). It watches 7 exchanges × 50 symbols every 50 ms and logs profitable directional spreads.

### The scanning pipeline (read in this order to understand the system)

1. **`exchange/BaseWsClient`** — abstract OkHttp `WebSocketListener` with two reconnect paths: server-initiated close (exponential backoff) and a watchdog virtual thread (15 s no message → reconnect; 20 s no book data → reconnect). `lastMessageAt` and `lastDataAt` are tracked separately so heartbeat pings don't mask a stale depth stream.

2. **`exchange/MultiConnectionWsClient`** — coordinator for exchanges that need multiple WS connections (Bybit: 5 shards × 10 symbols, HTX: 2 shards × 25 symbols). Pattern: `*WsClient` extends `MultiConnectionWsClient`, `*WsClientShard` extends `BaseWsClient`.

3. **`market/OrderBook`** — thread-safe `ConcurrentSkipListMap` for bids (reverse order) and asks. Key distinction: `applySnapshot()` (full replace) vs `applyDelta()` (level-by-level: qty=0 removes, qty>0 inserts/updates). Tracks `lastBestBidChangeTime` separately from `lastUpdateTime` to detect price freezes.

4. **`market/OrderBookManager`** — creates/retrieves books by `"exchange:symbol"` key, builds `Tick` objects for the scanner. Implements **price-freeze detection**: if a book's best bid hasn't changed for 120 s (even if depth updates are coming in), that symbol is excluded from scanning and a WARN is logged. Resolves automatically when the price moves again.

5. **`scanner/OpportunityScanner`** — runs every 50 ms on a virtual thread. For each of 50 symbols, pairs every reliable tick against every other reliable tick in both directions. Uses **session tracking**: a `(symbol, longExchange, shortExchange)` triple that stays above `minNetSpreadPercent` is tracked as an `ActiveSession` until it disappears for 10 consecutive scans (~500 ms gap). Logs `[ARB OPEN]` on new sessions, `[ARB LIVE]` every 30 s for long-running ones, `[ARB CLOSE]` when a session ends.

6. **`storage/OpportunityStore`** — queues writes to `LinkedBlockingQueue`, flushes to SQLite every 1 s in a batch. Three tables: `opportunities` (debounced raw ticks, 30 s write rate per pair, 48 h retention), `opportunity_sessions` (one row per closed session), `session_ticks` (time series for sessions ≥ 60 s, 7 d retention). SQLite WAL mode enabled. Prunes old data every 6 h.

### Key data unit conventions

- `opportunities.gross_spread_pct` and `net_spread_pct` — stored as **percent** (e.g. `0.37` = 0.37%)
- `opportunity_sessions.peak_net_pct`, `avg_net_pct`, etc. — also **percent**
- `SpreadCalculator.grossSpread()` and `netSpread()` — return **fractions** (e.g. `0.0037`). The scanner multiplies by 100 before logging/storing.
- `FeeEngine.getTotalRoundTripCost()` — returns a **fraction**

### Exchange-specific initialization quirks

Each exchange has its own snapshot-then-delta protocol:

- **Binance**: combined stream for all 50 symbols. Buffers WS deltas while fetching REST snapshot per symbol in parallel. `snapshotInProgress` `AtomicBoolean` gates delta application. Sequence check: `u <= lastAppliedU` skips; no gap-triggered reconnect (absolute quantities).
- **KuCoin**: REST snapshot after WS subscription using `data.sequence`. Pipe-delimited delta format (`price,side,qty`). Bitcoin symbol is `XBTUSDTM` — `SymbolRegistry` maps `XBT → BTC`.
- **Bybit / OKX / Bitget**: first WS message has `type=snapshot` (Bybit) or `action=snapshot` (OKX/Bitget); subsequent messages are deltas.
- **Gate.io**: delta-only stream (no snapshot message). Bot accumulates bid+ask deltas until both sides non-empty, then calls `applySnapshot()` to bootstrap. Subsequent messages go through `applyDelta()`. Fields: `result.s` (symbol), `result.b` (bids), `result.a` (asks), level format `{"p":"price","s":qty}`.
- **HTX**: all frames are GZIP-compressed binary — `handleBinaryMessage()` decompresses before parsing. Server sends `{"op":"ping","ts":...}` heartbeats; bot must reply `{"op":"pong","ts":...}`. Time endpoint field is `ts` (not `data`).

### Symbol mapping

`SymbolRegistry` maps canonical symbols (`BTC`) to exchange-specific ones (`BTCUSDT`, `XBTUSDTM`, `BTC-USDT-SWAP`, etc.) via `ExchangeFormat` enum. All 50 canonical symbols are verified to exist on all 7 exchanges. Adding a new exchange requires adding an `ExchangeFormat` enum value and corresponding `parseSymbols()` logic.

### Fee and funding

`FeeEngine` caches fee schedules and funding rates in `ConcurrentHashMap` keyed by `"exchange:symbol"`. Both are fetched once at startup (no background refresh in Phase 1). `getTotalRoundTripCost()` computes `2 × (takerA + takerB) + net_funding_cost` as a fraction.

`RiskFilter` silently returns `false` (no logging) for ticks that are unreliable, have `abs(fundingRate) > 0.1%`, or settle within 5 minutes.

### Dashboard

`DashboardServer` serves `index.html` from classpath and pushes SSE snapshots every 200 ms from a `SnapshotAssembler`. The assembler reads live state from `OrderBookManager`, `FeeEngine`, `HealthMonitor`, and `OpportunityStore`. `SystemStatsCollector` uses JMX `OperatingSystemMXBean.getProcessCpuLoad()` for bot CPU (normalized to total CPU capacity, matching profiler readings) and OSHI for system-wide stats.

### Adding a new exchange (checklist)

1. `exchange/<name>/<Name>WsClientShard` extends `BaseWsClient` — implement `wsUrl()`, `onConnected()`, `handleMessage()`. Call `recordDataReceived()` after every book update (not on heartbeat-only messages).
2. `exchange/<name>/<Name>WsClient` extends either `BaseWsClient` (single connection) or `MultiConnectionWsClient` (multiple shards).
3. `exchange/<name>/<Name>FeeClient` implements `ExchangeFeeClient` — `fetchFeeSchedule()` and `fetchFundingRate()`, never throw.
4. `SymbolRegistry.ExchangeFormat` — add enum value and `parseSymbols()` case.
5. `Main.java` — add to all four `switch` methods (`pingPathFor`, `timePathFor`, `timeFieldFor`, `symbolPathFor`) and both `buildFeeClient` / `buildWsClient` switches.
6. `application.conf` — add exchange block under `arbbot.exchanges`.
7. **Before enabling**: verify all 50 watched symbols exist on the new exchange via its API. Wrong symbol mappings cause real financial risk in Phase 2.
