# Order Book Management — Implementation Guide

## Snapshot-Then-Delta Pattern

1. Subscribe to incremental WS stream FIRST (start buffering messages immediately)
2. Fetch REST snapshot (takes 50–200ms)
3. Drop buffered deltas older than snapshot
4. Apply snapshot to local book
5. Apply buffered deltas that fall after snapshot sequence
6. Continue applying live deltas, validating sequence each time

## Per-Exchange Sequence Validation

### Binance
- Snapshot returns `lastUpdateId = N`
- Each delta has `U` (first update ID) and `u` (final update ID)
- Drop buffered deltas where `u < N + 1`
- First applied delta must satisfy `U <= N + 1 <= u`
- Subsequent: `U == prevFinalU + 1` (strict)
- On gap: set `initialized=false`, fetch new snapshot

### KuCoin
- Snapshot (via REST) returns `sequence = N`
- Each delta must have `sequence == lastApplied + 1`
- On gap: re-sync

### Bybit
- First WS message `type=snapshot` contains `seq=N`
- Each `type=delta` must have `seq == lastSeq + 1`
- On gap: re-subscribe (Bybit automatically resends snapshot)

### OKX
- Snapshot `action=snapshot` has `seqId=N, prevSeqId=-1`
- Each `action=update` must have `prevSeqId == lastSeqId`
- On gap: re-subscribe

## Thread Safety

- `OrderBook.applySnapshot()` is `synchronized` to prevent partial reads during snapshot replacement
- `applyDelta()` is not synchronized — deltas arrive on a single virtual thread per exchange
- `ConcurrentSkipListMap` allows lock-free concurrent reads from the scanner thread

## Depth-Adjusted Price Calculation

For buying `notionalUsdt` worth of asset (walking ask side, lowest price first):
```
remaining = notionalUsdt
totalQty = 0

for each (price, qty) in asks (ascending):
    levelValue = price * qty
    if levelValue >= remaining:
        totalQty += remaining / price
        remaining = 0
        break
    totalQty += qty
    remaining -= levelValue

if remaining > 0: return empty (insufficient depth)
avgPrice = notionalUsdt / totalQty
```

Same logic for selling (walk bid side, descending).

## Memory Efficiency

- Use Double keys/values (boxed) — acceptable for 400-level books (~6.4 KB per side)
- Remove zero-quantity levels immediately in `applyLevels()`
- Keep only top 200 levels in snapshot REST fetch (`limit=200`)
