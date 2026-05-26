# Arbitrage Bot — Architecture

## Phase 1: Detection (Implemented)

### Component Overview

```
WS Clients (4x) → OrderBookManager → OpportunityScanner → OpportunityStore (SQLite)
                         ↑                    ↑
                  SymbolRegistry          FeeEngine
                  ClockSync               RiskFilter
                  HealthMonitor           MetricsRegistry
```

### Data Flow

1. Each `*WsClient` receives depth updates on a virtual thread and calls `orderBookManager.applyDelta(exchange, symbol, deltas, seqNum)`
2. `OrderBookManager` holds one `OrderBook` per (exchange, canonicalSymbol); applies updates; triggers `healthMonitor.recordWsTick(exchange)` on every message
3. `OpportunityScanner` runs every 50ms on a virtual thread; calls `orderBookManager.getAllTicks(symbol, exchangeSymbols, orderSizeUsdt)` for each watched symbol
4. For each reliable tick pair in both directions, `SpreadCalculator.netSpread(tickA, tickB, feeEngine, holdingHours)` computes the net spread
5. `RiskFilter.passes(longTick, shortTick, longFunding, shortFunding)` checks funding rate and feed freshness
6. Qualifying opportunities are queued in `OpportunityStore` (LinkedBlockingQueue) and flushed to SQLite every `flushIntervalMs`

### Threading Model

- One virtual thread per WS client (`onMessage` callback — I/O bound, blocked only on JSON parse)
- One virtual-thread-backed `ScheduledExecutorService` for scanner (50ms fixed period)
- One virtual-thread-backed `ScheduledExecutorService` for fee refresh
- One virtual-thread-backed `ScheduledExecutorService` for health monitor staleness checks
- One dedicated virtual thread in `OpportunityStore` drains the `LinkedBlockingQueue`
- `OrderBook`: `ConcurrentSkipListMap` allows lock-free concurrent reads from scanner; `applySnapshot()` is synchronized; deltas arrive on a single virtual thread per exchange so no lock needed on delta path
- `FeeEngine`: `ConcurrentHashMap` cache; updates from fee scheduler thread are atomic map operations

### Class Responsibilities

| Class | Responsibility |
|---|---|
| `Main` | 13-step startup sequence; wires all components; registers shutdown hook |
| `AppConfig` | Reads HOCON config; provides typed nested records |
| `Exchange` | Interface: name, connect, disconnect, isConnected, placeOrder (Phase 2 stub) |
| `BaseWsClient` | Exponential backoff reconnect; heartbeat scheduling; send() |
| `*WsClient` | Exchange-specific: URL, subscription, message parsing, snapshot fetch |
| `HealthMonitor` | Per-exchange health state; staleness detection; WARN on transition |
| `EndpointChecker` | HTTP ping with timeout; returns ExchangeHealth |
| `SymbolRegistry` | Canonical ↔ exchange symbol mapping; inverse contract exclusion |
| `ClockSync` | Server-time fetch; RTT-corrected offset; warns if >500ms |
| `FeeEngine` | Fee schedule + funding rate cache; getTotalRoundTripCost() |
| `*FeeClient` | Exchange-specific REST fee/funding fetch with HMAC signing |
| `OrderBook` | Thread-safe ConcurrentSkipListMap L2 book; depth-adjusted pricing; sequence gap detection |
| `OrderBookManager` | Registry of OrderBook instances; Tick generation; re-sync triggering |
| `SpreadCalculator` | grossSpread() and netSpread() static methods |
| `RiskFilter` | Funding rate guard; funding settlement buffer; stale feed guard |
| `OpportunityScanner` | 50ms scan loop; evaluates all directional pairs; emits Opportunity |
| `OpportunityStore` | LinkedBlockingQueue buffer; batched SQLite INSERT; queryStats() |
| `MetricsRegistry` | Micrometer counters, gauges, timers for all 10 metrics |
| `RateLimiter` | Token bucket; one instance per exchange for REST rate limiting |
| `HmacSha256` | hex() and base64() signing utilities |
| `ClockSync` | Server-time synchronization; adjusted now() per exchange |

---

## Phase 2: Execution Engine (Design — Not Implemented)

### Overview

Phase 2 fires both trade legs simultaneously via `CompletableFuture`, monitors fills, and handles partial fills or failures within a configurable timeout.

### Core Interface

```java
public interface ExecutionEngine {
    CompletableFuture<ExecutionResult> execute(Opportunity opportunity, double sizeUsdt);
}

public record ExecutionResult(
    UUID opportunityId,
    LegResult longLeg,
    LegResult shortLeg,
    ExecutionStatus status,
    Instant executedAt
) {}

public sealed interface LegResult permits
    LegResult.Filled, LegResult.PartialFill, LegResult.Rejected, LegResult.Timeout, LegResult.Error {

    record Filled(String orderId, double avgPrice, double filledQty, double fee) implements LegResult {}
    record PartialFill(String orderId, double avgPrice, double filledQty, double requestedQty, double fee) implements LegResult {}
    record Rejected(String reason, int exchangeCode) implements LegResult {}
    record Timeout(long waitedMs) implements LegResult {}
    record Error(String message, Throwable cause) implements LegResult {}
}

public enum ExecutionStatus {
    BOTH_FILLED,
    PARTIAL_BOTH,
    LONG_FILLED_SHORT_FAILED,
    SHORT_FILLED_LONG_FAILED,
    BOTH_FAILED,
    TIMEOUT
}
```

### Order Placement Endpoints

```
Binance: POST /fapi/v1/order
  Body: symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.001&timestamp=X&signature=X
  Header: X-MBX-APIKEY

KuCoin: POST /api/v1/orders
  Body: {"clientOid":"uuid","side":"buy","symbol":"BTCUSDTM","type":"market","size":1,"leverage":5}
  Headers: KC-API-KEY, KC-API-SIGN, KC-API-TIMESTAMP, KC-API-PASSPHRASE

Bybit: POST /v5/order/create
  Body: {"category":"linear","symbol":"BTCUSDT","side":"Buy","orderType":"Market","qty":"0.001"}
  Headers: X-BAPI-API-KEY, X-BAPI-SIGN, X-BAPI-TIMESTAMP, X-BAPI-RECV-WINDOW

OKX: POST /api/v5/trade/order
  Body: {"instId":"BTC-USDT-SWAP","tdMode":"cross","side":"buy","ordType":"market","sz":"1"}
  Headers: OK-ACCESS-KEY, OK-ACCESS-SIGN, OK-ACCESS-TIMESTAMP, OK-ACCESS-PASSPHRASE
```

### Dual-Leg Outcome Matrix

| Long Leg | Short Leg | Action |
|---|---|---|
| Filled | Filled | Open position; start convergence monitoring |
| Filled | Failed/Timeout | Emergency close long (market sell on long exchange) |
| Failed/Timeout | Filled | Emergency close short (market buy on short exchange) |
| Partial | Partial | Open position at min(longQty, shortQty); cancel/close excess |
| Failed | Failed | Log; no further action |

### Position State Machine

```
PENDING_OPEN
    → (both legs confirmed) → OPEN
    → (one leg fails) → EMERGENCY_CLOSE

OPEN
    → (spread converged to minExitNetSpread) → PENDING_CLOSE
    → (maxHoldingHours exceeded) → PENDING_CLOSE
    → (funding cost accumulation > remaining gross spread) → PENDING_CLOSE

PENDING_CLOSE
    → (both close legs filled) → CLOSED

EMERGENCY_CLOSE
    → (emergency leg filled) → CLOSED
    → (emergency leg fails 3×) → MANUAL_INTERVENTION (Telegram alert)
```

### Phase 2 Risk Controls

- `maxPositionSizeUsdt` — hard cap per position (e.g., $5,000)
- `maxTotalOpenNotionalUsdt` — sum of all open positions (e.g., $20,000)
- `maxDailyDrawdownPct` — halt new trades if daily PnL < -X% of capital
- `maxLeveragePerExchange` — per-exchange leverage cap
- `liquidationMarginRatioWarning` — alert when margin ratio < 20% on any exchange

---

## Phase 3: Position Management (Design — Not Implemented)

### Convergence Detection Loop (every 5 seconds)

1. Fetch current ticks for both legs from `OrderBookManager`
2. Compute current spread = `shortExchangeBid - longExchangeAsk`
3. If `spread < minExitNetSpread` (e.g., 0.01%) → trigger `PENDING_CLOSE`
4. If spread has been negative for > 10 minutes → force close (spread inverted)

### Exit Conditions (Priority Order)

1. Spread < `minExitNetSpread` — profitable exit
2. Max holding time exceeded (`maxHoldingHours`, e.g., 24h)
3. Accumulated funding cost exceeds remaining gross spread
4. Funding settlement within `minFundingTimeBufferMinutes` and no profit yet

### PnL Attribution (per closed position)

```
gross_spread_captured     = short_entry_price - long_entry_price (normalized by mid-price)
entry_slippage            = (actual_long_avg - best_ask_at_detection) + (best_bid_at_detection - actual_short_avg)
exit_slippage             = same for close legs
fees_paid                 = sum of all 4 leg fees (entry long, entry short, exit long, exit short)
funding_net               = sum of all funding payments received/paid during holding period
net_pnl                   = gross_spread_captured - entry_slippage - exit_slippage - fees_paid - funding_net
```

---

## Phase 4: Operations (Design — Not Implemented)

### Telegram Alerts

Trigger on:
- Opportunity with net spread > 0.3%
- `EMERGENCY_CLOSE` state entered
- Exchange goes offline (HealthMonitor detects)
- Daily loss limit approached (>80% of `maxDailyDrawdownPct`)

Format: `🚨 ARB: BTC Binance→Bybit +0.18% net | $1000 | 14:32:07 UTC`

### Web Dashboard (Spring Boot minimal)

Single HTML page served from `GET /` backed by `GET /api/stats` (reads from SQLite):
- Live opportunities table (last 100)
- Exchange health grid (rest/ws status + latency per exchange)
- 24h opportunity chart (net spread distribution)
- Running PnL if Phase 2 active

### Automated Collateral Rebalancing

- Monitor free margin per exchange every 5 minutes
- Alert via Telegram if any exchange margin < 30% of target allocation
- In Phase 4: auto-transfer between exchanges via exchange transfer APIs

### Performance Analytics

- Sharpe ratio (daily): `mean(daily_pnl) / stddev(daily_pnl) * sqrt(252)`
- Opportunity → trade conversion rate: `trades_executed / opportunities_detected`
- Average holding time per symbol pair
- Fee drag: `total_fees_paid / total_gross_spread_captured`
- Funding drag: `net_funding_paid / total_gross_spread_captured`
