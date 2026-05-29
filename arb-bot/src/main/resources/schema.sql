CREATE TABLE IF NOT EXISTS opportunities (
    id TEXT PRIMARY KEY,
    canonical_symbol TEXT NOT NULL,
    long_exchange TEXT NOT NULL,
    long_ask_price REAL NOT NULL,
    long_best_bid REAL NOT NULL DEFAULT 0,
    short_exchange TEXT NOT NULL,
    short_bid_price REAL NOT NULL,
    short_best_ask REAL NOT NULL DEFAULT 0,
    gross_spread_pct REAL NOT NULL,
    net_spread_pct REAL NOT NULL,
    estimated_cost_pct REAL NOT NULL,
    long_funding_rate REAL,
    short_funding_rate REAL,
    long_next_funding TEXT,
    short_next_funding TEXT,
    order_size_usdt REAL NOT NULL,
    max_volume_usdt REAL NOT NULL DEFAULT 0,
    long_ask_depth_usdt REAL NOT NULL DEFAULT 0,
    short_bid_depth_usdt REAL NOT NULL DEFAULT 0,
    detected_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_opp_symbol ON opportunities(canonical_symbol);
CREATE INDEX IF NOT EXISTS idx_opp_time ON opportunities(detected_at);
CREATE INDEX IF NOT EXISTS idx_opp_net_spread ON opportunities(net_spread_pct DESC);
CREATE INDEX IF NOT EXISTS idx_opp_volume ON opportunities(max_volume_usdt DESC);

CREATE TABLE IF NOT EXISTS opportunity_sessions (
    id TEXT PRIMARY KEY,
    canonical_symbol TEXT NOT NULL,
    long_exchange TEXT NOT NULL,
    short_exchange TEXT NOT NULL,
    started_at TEXT NOT NULL,
    ended_at TEXT NOT NULL,
    peak_net_pct REAL NOT NULL,
    avg_net_pct REAL NOT NULL,
    min_net_pct REAL NOT NULL DEFAULT 0,
    entry_net_pct REAL NOT NULL DEFAULT 0,
    exit_net_pct REAL NOT NULL DEFAULT 0,
    peak_volume_usdt REAL NOT NULL DEFAULT 0,
    avg_volume_usdt REAL NOT NULL DEFAULT 0,
    duration_ms INTEGER NOT NULL,
    tick_count INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sess_symbol ON opportunity_sessions(canonical_symbol);
CREATE INDEX IF NOT EXISTS idx_sess_ended ON opportunity_sessions(ended_at DESC);

-- Per-tick reconstruction of every opportunity session.
-- Allows replay of spread profile, volume at each tick, and entry/exit price analysis.
CREATE TABLE IF NOT EXISTS session_ticks (
    session_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    recorded_at TEXT NOT NULL,
    net_pct REAL NOT NULL,
    gross_pct REAL NOT NULL,
    max_volume_usdt REAL NOT NULL,
    long_ask REAL NOT NULL,
    short_bid REAL NOT NULL,
    PRIMARY KEY (session_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_tick_session ON session_ticks(session_id);
CREATE INDEX IF NOT EXISTS idx_tick_time ON session_ticks(recorded_at);
