# Library Comparison — WebSocket & Concurrency Decisions

## WebSocket Library: OkHttp 4.x ✅ CHOSEN

### Candidates Evaluated

| Library | Pros | Cons |
|---|---|---|
| **OkHttp 4.x** | Already a dep; TLS built-in; shared connection pool; battle-tested; clean listener API | No built-in reconnect (implement ourselves in BaseWsClient) |
| Java-WebSocket (TooTallNate) | Dedicated WS lib; simple | Extra dep; less ecosystem; no HTTP/2 |
| Netty WS codec | Highest throughput; full control | Heavy setup; requires Netty expertise |
| Tyrus (Jakarta WS RI) | JSR-356 standard | Verbose; heavier runtime |

### Decision: OkHttp 4.x

- **Zero additional dependencies** — already required for REST calls
- `OkHttpClient` connection pool efficiently handles 50+ concurrent WebSocket connections
- `WebSocketListener` callbacks are straightforward to unit-test with `MockWebServer`
- TLS handled transparently
- Reconnect implemented in `BaseWsClient` abstract class with exponential backoff

## Concurrency: Java 21 Virtual Threads ✅ CHOSEN

### Rationale

- Each WS client's `onMessage` handler runs on a virtual thread — I/O-bound; blocks only on JSON parse
- OpportunityScanner runs every 50ms on a virtual-thread-backed ScheduledExecutorService
- Fee refresh schedulers: `Executors.newScheduledThreadPool(1, Thread.ofVirtual().name("fee-scheduler").factory())`
- No reactive frameworks needed — virtual threads give same throughput with simpler blocking code
- `ConcurrentSkipListMap` for order books handles concurrent reads (scanner) and writes (WS callbacks)

## Order Book Data Structure: ConcurrentSkipListMap

- Bids: `new ConcurrentSkipListMap<>(Comparator.reverseOrder())` — highest price first, O(log n) head access
- Asks: `new ConcurrentSkipListMap<>()` — natural ascending order
- Lock-free concurrent reads for depth walks
- Insertion/deletion O(log n)
- ~400 levels × 2 sides × 16 bytes/entry ≈ 13 KB per symbol per exchange — acceptable

## JSON: Jackson ObjectMapper (singleton, thread-safe)

- Configured with `JavaTimeModule` for `Instant` serialization
- `FAIL_ON_UNKNOWN_PROPERTIES` disabled — exchange APIs add fields without notice
- Shared `private static final ObjectMapper mapper` in each client class
