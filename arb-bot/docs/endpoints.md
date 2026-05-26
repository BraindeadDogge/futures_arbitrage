# All API Endpoints Used

| Exchange | Type | Endpoint | Auth | Purpose |
|---|---|---|---|---|
| Binance | REST | GET /fapi/v1/ping | No | Health check |
| Binance | REST | GET /fapi/v1/time | No | Server time |
| Binance | REST | GET /fapi/v1/exchangeInfo | No | Active symbols |
| Binance | REST | GET /fapi/v1/depth?symbol=X&limit=200 | No | Order book snapshot |
| Binance | REST | GET /fapi/v1/commissionRate?symbol=X | Yes | Fee rates |
| Binance | REST | GET /fapi/v1/premiumIndex?symbol=X | No | Funding rate |
| Binance | WS | wss://fstream.binance.com/stream?streams=X@depth@100ms | No | Depth stream |
| KuCoin | REST | GET /api/v1/timestamp | No | Server time |
| KuCoin | REST | GET /api/v1/contracts/active | No | Active symbols + fees |
| KuCoin | REST | GET /api/v1/funding-rate/{symbol}/current | No | Funding rate |
| KuCoin | REST | POST /api/v1/bullet-public | No | WS auth token |
| KuCoin | WS | wss://ws-api-futures.kucoin.com/endpoint?token=X | Token | Depth stream |
| Bybit | REST | GET /v5/market/time | No | Server time |
| Bybit | REST | GET /v5/market/instruments-info?category=linear | No | Active symbols |
| Bybit | REST | GET /v5/market/tickers?category=linear&symbol=X | No | Funding rate |
| Bybit | REST | GET /v5/account/fee-rate?category=linear&symbol=X | Yes | Fee rates |
| Bybit | WS | wss://stream.bybit.com/v5/public/linear | No | Depth stream |
| OKX | REST | GET /api/v5/public/time | No | Server time |
| OKX | REST | GET /api/v5/public/instruments?instType=SWAP | No | Active symbols |
| OKX | REST | GET /api/v5/public/funding-rate?instId=X | No | Funding rate |
| OKX | REST | GET /api/v5/account/trade-fee?instType=SWAP&instId=X | Yes | Fee rates |
| OKX | WS | wss://ws.okx.com:8443/ws/v5/public | No | Depth stream |
