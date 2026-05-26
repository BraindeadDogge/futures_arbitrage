# Exchange REST APIs for Fees & Funding — Research Findings

## Binance Futures

**Server time:** `GET /fapi/v1/time` → `{"serverTime": 1234567890123}`
**Active symbols:** `GET /fapi/v1/exchangeInfo`
  → `result.symbols[]` where `contractType=PERPETUAL` AND `marginAsset=USDT` (excludes inverse)
**Fee rate (authenticated):** `GET /fapi/v1/commissionRate?symbol=BTCUSDT&timestamp=X&signature=X`
  Header: `X-MBX-APIKEY: {apiKey}`
  Response: `{"symbol":"BTCUSDT","makerCommissionRate":"0.0002","takerCommissionRate":"0.0004"}`
**Funding rate (public):** `GET /fapi/v1/premiumIndex?symbol=BTCUSDT`
  Response: `{"symbol":"BTCUSDT","lastFundingRate":"0.0001","nextFundingTime":1234567890000}`
**Signing:** `signature = HMAC-SHA256(secretKey, queryString + "&timestamp=" + ts)` — append `&signature=X`
**Default taker fallback (no API key):** 0.0005 (0.05%)

## KuCoin Futures

**Server time:** `GET /api/v1/timestamp` → `{"data": 1234567890123}`
**Active symbols:** `GET /api/v1/contracts/active`
  → `data[].symbol` where `isInverse=false`. Also contains `makerFeeRate`, `takerFeeRate`, `multiplier`
**Funding rate (public):** `GET /api/v1/funding-rate/{symbol}/current`
  Response: `{"data":{"symbol":"BTCUSDTM","value":0.0001,"predictedValue":0.00009,"timePoint":X}}`
**Default taker fallback:** 0.0006 (0.06%)

## Bybit

**Server time:** `GET /v5/market/time` → `{"result":{"timeSecond":"1234567890","timeNano":"..."}}`
**Active symbols:** `GET /v5/market/instruments-info?category=linear`
  → `result.list[]` where `contractType=LinearPerpetual`
**Funding rate (public):** `GET /v5/market/tickers?category=linear&symbol=BTCUSDT`
  Response: `{"result":{"list":[{"fundingRate":"0.0001","nextFundingTime":"1234567890000"}]}}`
**Fee rate (authenticated):** `GET /v5/account/fee-rate?category=linear&symbol=BTCUSDT`
  Headers: `X-BAPI-API-KEY`, `X-BAPI-SIGN`, `X-BAPI-TIMESTAMP`, `X-BAPI-RECV-WINDOW`
  Response: `{"result":{"list":[{"symbol":"BTCUSDT","makerFeeRate":"0.0001","takerFeeRate":"0.0006"}]}}`
**Signing:** `signature = HMAC-SHA256(secretKey, timestamp + apiKey + recvWindow + queryString)`
**Default taker fallback:** 0.0006 (0.06%)

## OKX

**Server time:** `GET /api/v5/public/time` → `{"data":[{"ts":"1234567890000"}]}`
**Active symbols:** `GET /api/v5/public/instruments?instType=SWAP`
  → `data[]` where `ctType=linear` (USDT-margined perpetuals)
  Contains: `ctVal` (contract multiplier), `tickSz`, `minSz`, `lotSz`
**Funding rate (public):** `GET /api/v5/public/funding-rate?instId=BTC-USDT-SWAP`
  Response: `{"data":[{"fundingRate":"0.0001","nextFundingTime":"1234567890000"}]}`
**Fee rate (authenticated):** `GET /api/v5/account/trade-fee?instType=SWAP&instId=BTC-USDT-SWAP`
  Headers: `OK-ACCESS-KEY`, `OK-ACCESS-SIGN`, `OK-ACCESS-TIMESTAMP`, `OK-ACCESS-PASSPHRASE`
  Response: `{"data":[{"instType":"SWAP","makerU":"-0.0001","takerU":"0.0006"}]}`
  Note: negative `makerU` = maker rebate (you receive money)
**Signing:** `signature = Base64(HMAC-SHA256(secretKey, timestamp + "GET" + path + body))`
**Default taker fallback:** 0.0005 (0.05%)
