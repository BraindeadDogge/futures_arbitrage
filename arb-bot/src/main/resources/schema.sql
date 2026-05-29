CREATE TABLE IF NOT EXISTS opportunities (
    id TEXT PRIMARY KEY,
    canonical_symbol TEXT NOT NULL,
    long_exchange TEXT NOT NULL,
    long_ask_price REAL NOT NULL,
    short_exchange TEXT NOT NULL,
    short_bid_price REAL NOT NULL,
    gross_spread_pct REAL NOT NULL,
    net_spread_pct REAL NOT NULL,
    estimated_cost_pct REAL NOT NULL,
    long_funding_rate REAL,
    short_funding_rate REAL,
    long_next_funding TEXT,
    short_next_funding TEXT,
    order_size_usdt REAL NOT NULL,
    detected_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_opp_symbol ON opportunities(canonical_symbol);
CREATE INDEX IF NOT EXISTS idx_opp_time ON opportunities(detected_at);
CREATE INDEX IF NOT EXISTS idx_opp_net_spread ON opportunities(net_spread_pct DESC);

CREATE TABLE IF NOT EXISTS opportunity_sessions (
    id TEXT PRIMARY KEY,
    canonical_symbol TEXT NOT NULL,
    long_exchange TEXT NOT NULL,
    short_exchange TEXT NOT NULL,
    started_at TEXT NOT NULL,
    ended_at TEXT NOT NULL,
    peak_net_pct REAL NOT NULL,
    avg_net_pct REAL NOT NULL,
    duration_ms INTEGER NOT NULL,
    tick_count INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sess_symbol ON opportunity_sessions(canonical_symbol);
CREATE INDEX IF NOT EXISTS idx_sess_ended ON opportunity_sessions(ended_at DESC);
