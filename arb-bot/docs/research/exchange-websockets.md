# Exchange WebSocket APIs — Research Findings

## Binance Futures (fstream.binance.com)

**Base WS URL:** `wss://fstream.binance.com/stream?streams=`
**Auth:** None for public depth streams.
**Depth stream:** `btcusdt@depth@100ms` — incremental 100ms updates
**Snapshot endpoint:** `GET /fapi/v1/depth?symbol=BTCUSDT&limit=200`
**Snapshot response:**
```json
{"lastUpdateId": 12345, "bids": [["50000.0","1.2"]], "asks": [["50001.0","0.8"]]}
```
**Delta message format:**
```json
{"e":"depthUpdate","E":1234567890,"s":"BTCUSDT","U":12340,"u":12345,"b":[["50000.0","1.5"]],"a":[["50001.0","0.0"]]}
```
- `U` = first update ID in event, `u` = final update ID in event
- qty=0 means remove level

**Sequence validation:** Buffer all deltas while fetching snapshot. After snapshot (lastUpdateId=N):
1. Drop buffered deltas where `u < N + 1`
2. Apply first delta where `U <= N + 1 <= u`
3. Subsequent deltas: require `U == prevFinalU + 1`
4. On gap: mark book uninitialized, re-fetch snapshot

**Heartbeat:** Send `{"method":"ping"}` every 3 minutes. Binance also sends ping frames that must be ponged.
**Max streams per connection:** 200
**Reconnect:** Binance closes connection after 24 hours.

---

## KuCoin Futures (api-futures.kucoin.com)

**Get WS token first:** `POST /api/v1/bullet-public` (no auth for public)
```json
{
  "data": {
    "token": "abc123",
    "instanceServers": [{"endpoint": "wss://ws-api-futures.kucoin.com/endpoint", "pingInterval": 18000, "pingTimeout": 10000}]
  }
}
```
**Connect URL:** `{endpoint}?token={token}&connectId={uuid4}`
**Subscribe:**
```json
{"id":"1","type":"subscribe","topic":"/contractMarket/level2:BTCUSDTM","privateChannel":false,"response":true}
```
**Delta message format:**
```json
{"topic":"/contractMarket/level2:BTCUSDTM","type":"message","data":{"sequence":12346,"change":"50000.0,buy,1.5|50001.0,sell,0","timestamp":1234567890}}
```
Change format: `price,side,qty` — `buy`=bid, `sell`=ask, qty=0 means remove level.

**Sequence validation:** Each delta `sequence` must equal `lastApplied + 1`; gap triggers re-sync.
**Heartbeat:** Send `{"id":"uuid","type":"ping"}` every `pingInterval` ms (18 seconds). Expect `{"type":"pong"}`.

---

## Bybit (stream.bybit.com)

**WS URL:** `wss://stream.bybit.com/v5/public/linear`
**Subscribe:**
```json
{"op":"subscribe","args":["orderbook.50.BTCUSDT"]}
```
**Snapshot (first message):**
```json
{"topic":"orderbook.50.BTCUSDT","type":"snapshot","ts":1234567890,"data":{"s":"BTCUSDT","b":[["50000.0","1.2"]],"a":[["50001.0","0.8"]],"seq":12345,"u":1}}
```
**Delta message:**
```json
{"topic":"orderbook.50.BTCUSDT","type":"delta","ts":1234567890,"data":{"s":"BTCUSDT","b":[],"a":[["50001.0","0.0"]],"seq":12346,"u":2}}
```
- `seq` must increment by 1. Gap: re-subscribe (Bybit re-sends snapshot on new subscribe).
- qty=0 means remove level.

**Heartbeat:** Send `{"op":"ping"}` every 20 seconds. Expect `{"op":"pong","ret_msg":"pong","conn_id":"...","ret_code":0}`.
**Max topics per connection:** 10 recommended.

---

## OKX (ws.okx.com)

**WS URL:** `wss://ws.okx.com:8443/ws/v5/public`
**Subscribe:**
```json
{"op":"subscribe","args":[{"channel":"books","instId":"BTC-USDT-SWAP"}]}
```
- `books` = 400-level book, 100ms push frequency
- `books5` = top-5 levels only (lower bandwidth)
- `books-l2-tbt` = tick-by-tick full book

**Snapshot message:**
```json
{"arg":{"channel":"books","instId":"BTC-USDT-SWAP"},"action":"snapshot","data":[{"bids":[["50000","1.2","0","1"]],"asks":[["50001","0.8","0","1"]],"ts":"1234567890","seqId":12345,"prevSeqId":-1}]}
```
Format: `[price, qty, deprecated, numOrders]`

**Delta message:** `"action":"update"` — same format, qty=0 removes level.
**Sequence validation:** Each update's `prevSeqId` must equal previous message's `seqId`. Snapshot has `prevSeqId=-1`.
**Heartbeat:** Send string `"ping"` every 25 seconds. Expect string `"pong"`.

---

## Connection Management (All Exchanges)

- Reconnect with exponential backoff: 100ms → 200ms → 400ms → ... → 30s cap
- On reconnect: re-fetch snapshot (Binance) or re-subscribe (Bybit/OKX auto-sends snapshot)
- Mark feed stale if no tick received in `wsStaleThresholdMs` (default 2000ms)
- Use a single `OkHttpClient` instance (shared connection pool) across all WS connections
- Virtual threads for WS message handlers (I/O bound, non-blocking model via virtual threads)
