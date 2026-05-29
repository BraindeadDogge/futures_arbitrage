package com.arbbot.storage;

import com.arbbot.scanner.Opportunity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class OpportunityStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpportunityStore.class);

    private final String dbPath;
    private final long flushIntervalMs;
    private final BlockingQueue<Opportunity> buffer = new LinkedBlockingQueue<>();
    private final BlockingQueue<OpportunitySession> sessionBuffer = new LinkedBlockingQueue<>();
    private final BlockingQueue<List<OpportunityTick>> tickBatchBuffer = new LinkedBlockingQueue<>();
    private Connection connection;
    private ScheduledExecutorService scheduler;

    public record OpportunityStats(
        long totalOpportunities,
        double avgNetSpreadPct,
        double maxNetSpreadPct,
        Map<String, Long> countBySymbol,
        Map<String, Long> countByExchangePair,
        java.time.Instant firstSeen,
        java.time.Instant lastSeen
    ) {}

    public record OpportunitySession(
        String id,
        String symbol,
        String longExchange,
        String shortExchange,
        java.time.Instant startedAt,
        java.time.Instant endedAt,
        double peakNetPct,
        double avgNetPct,
        double minNetPct,
        double entryNetPct,
        double exitNetPct,
        double peakVolumeUsdt,
        double avgVolumeUsdt,
        long durationMs,
        int tickCount
    ) {}

    public record OpportunityTick(
        String sessionId,
        int seq,
        java.time.Instant recordedAt,
        double netPct,
        double grossPct,
        double maxVolumeUsdt,
        double longAsk,
        double shortBid
    ) {}

    public OpportunityStore(String dbPath, long flushIntervalMs) {
        this.dbPath = dbPath;
        this.flushIntervalMs = flushIntervalMs;
    }

    public void start() throws SQLException {
        java.io.File parent = new java.io.File(dbPath).getParentFile();
        if (parent != null) parent.mkdirs();
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        connection.setAutoCommit(false);
        initSchema();
        scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("store-flusher").factory());
        scheduler.scheduleAtFixedRate(this::flushSilent, flushIntervalMs, flushIntervalMs,
            TimeUnit.MILLISECONDS);
        log.info("OpportunityStore started: {}", dbPath);
    }

    private void initSchema() throws SQLException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("schema.sql")) {
            if (is == null) throw new SQLException("schema.sql not found on classpath");
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement stmt = connection.createStatement()) {
                for (String s : sql.split(";")) {
                    if (s.isBlank()) continue;
                    String trimmed = s.trim();
                    try {
                        stmt.execute(trimmed);
                    } catch (SQLException e) {
                        // CREATE TABLE must succeed; INDEX failures are tolerated
                        // (column may not exist yet on an older DB — migrations add it next)
                        if (trimmed.toUpperCase().startsWith("CREATE TABLE")) {
                            throw new SQLException("Schema init failed on: " + trimmed, e);
                        }
                        log.debug("Schema statement deferred (will retry after migrations): {}", e.getMessage());
                    }
                }
            }
            connection.commit();
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Schema init failed", e);
        }
        runMigrations();
    }

    /** Migrations for columns and indexes added after initial schema creation. */
    private void runMigrations() {
        // Each entry: [sql statement, expected-error substring to ignore]
        String[][] migrations = {
            { "ALTER TABLE opportunities ADD COLUMN long_best_bid REAL NOT NULL DEFAULT 0",        "duplicate column" },
            { "ALTER TABLE opportunities ADD COLUMN short_best_ask REAL NOT NULL DEFAULT 0",       "duplicate column" },
            { "ALTER TABLE opportunities ADD COLUMN max_volume_usdt REAL NOT NULL DEFAULT 0",      "duplicate column" },
            { "ALTER TABLE opportunities ADD COLUMN long_ask_depth_usdt REAL NOT NULL DEFAULT 0",  "duplicate column" },
            { "ALTER TABLE opportunities ADD COLUMN short_bid_depth_usdt REAL NOT NULL DEFAULT 0", "duplicate column" },
            { "ALTER TABLE opportunity_sessions ADD COLUMN min_net_pct REAL NOT NULL DEFAULT 0",   "duplicate column" },
            { "ALTER TABLE opportunity_sessions ADD COLUMN entry_net_pct REAL NOT NULL DEFAULT 0", "duplicate column" },
            { "ALTER TABLE opportunity_sessions ADD COLUMN exit_net_pct REAL NOT NULL DEFAULT 0",  "duplicate column" },
            { "ALTER TABLE opportunity_sessions ADD COLUMN peak_volume_usdt REAL NOT NULL DEFAULT 0", "duplicate column" },
            { "ALTER TABLE opportunity_sessions ADD COLUMN avg_volume_usdt REAL NOT NULL DEFAULT 0",  "duplicate column" },
            // Index that depends on max_volume_usdt — must come after the column migration above
            { "CREATE INDEX IF NOT EXISTS idx_opp_volume ON opportunities(max_volume_usdt DESC)", "already exists" },
        };
        try (Statement stmt = connection.createStatement()) {
            for (String[] m : migrations) {
                try {
                    stmt.execute(m[0]);
                    connection.commit();
                } catch (SQLException e) {
                    if (!e.getMessage().contains(m[1])) {
                        log.warn("Migration skipped [{}]: {}", m[0], e.getMessage());
                    }
                    try { connection.rollback(); } catch (SQLException ignored) {}
                }
            }
        } catch (SQLException e) {
            log.error("Migration setup failed: {}", e.getMessage());
        }
    }

    public void save(Opportunity opp) {
        buffer.offer(opp);
    }

    public void saveSession(OpportunitySession session) {
        sessionBuffer.offer(session);
    }

    public void saveSessionTicks(List<OpportunityTick> ticks) {
        if (!ticks.isEmpty()) tickBatchBuffer.offer(ticks);
    }

    public synchronized void flush() {
        flushOpportunities();
        flushSessions();
        flushSessionTicks();
    }

    private void flushOpportunities() {
        List<Opportunity> batch = new ArrayList<>();
        buffer.drainTo(batch);
        if (batch.isEmpty()) return;
        String sql = """
            INSERT OR IGNORE INTO opportunities
            (id, canonical_symbol, long_exchange, long_ask_price, long_best_bid,
             short_exchange, short_bid_price, short_best_ask,
             gross_spread_pct, net_spread_pct, estimated_cost_pct,
             long_funding_rate, short_funding_rate, long_next_funding, short_next_funding,
             order_size_usdt, max_volume_usdt, long_ask_depth_usdt, short_bid_depth_usdt,
             detected_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (Opportunity o : batch) {
                ps.setString(1, o.id().toString());
                ps.setString(2, o.canonicalSymbol());
                ps.setString(3, o.longExchange());
                ps.setDouble(4, o.longExchangeAskPrice());
                ps.setDouble(5, o.longExchangeBestBid());
                ps.setString(6, o.shortExchange());
                ps.setDouble(7, o.shortExchangeBidPrice());
                ps.setDouble(8, o.shortExchangeBestAsk());
                ps.setDouble(9, o.grossSpreadPct());
                ps.setDouble(10, o.netSpreadPct());
                ps.setDouble(11, o.estimatedTotalCostPct());
                ps.setDouble(12, o.longExchangeFunding() != null ? o.longExchangeFunding().currentRate() : 0);
                ps.setDouble(13, o.shortExchangeFunding() != null ? o.shortExchangeFunding().currentRate() : 0);
                ps.setString(14, o.longExchangeFunding() != null ? o.longExchangeFunding().nextSettlement().toString() : null);
                ps.setString(15, o.shortExchangeFunding() != null ? o.shortExchangeFunding().nextSettlement().toString() : null);
                ps.setDouble(16, o.orderSizeUsdt());
                ps.setDouble(17, o.maxVolumeUsdt());
                ps.setDouble(18, o.longAskDepthUsdt());
                ps.setDouble(19, o.shortBidDepthUsdt());
                ps.setString(20, o.detectedAt().toString());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
            log.debug("Flushed {} opportunities to SQLite", batch.size());
        } catch (SQLException e) {
            log.error("Opportunities flush failed: {}", e.getMessage());
            try { connection.rollback(); } catch (SQLException ignored) {}
        }
    }

    private void flushSessions() {
        List<OpportunitySession> batch = new ArrayList<>();
        sessionBuffer.drainTo(batch);
        if (batch.isEmpty()) return;
        String sql = """
            INSERT OR IGNORE INTO opportunity_sessions
            (id, canonical_symbol, long_exchange, short_exchange,
             started_at, ended_at,
             peak_net_pct, avg_net_pct, min_net_pct, entry_net_pct, exit_net_pct,
             peak_volume_usdt, avg_volume_usdt,
             duration_ms, tick_count)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (OpportunitySession s : batch) {
                ps.setString(1, s.id());
                ps.setString(2, s.symbol());
                ps.setString(3, s.longExchange());
                ps.setString(4, s.shortExchange());
                ps.setString(5, s.startedAt().toString());
                ps.setString(6, s.endedAt().toString());
                ps.setDouble(7, s.peakNetPct());
                ps.setDouble(8, s.avgNetPct());
                ps.setDouble(9, s.minNetPct());
                ps.setDouble(10, s.entryNetPct());
                ps.setDouble(11, s.exitNetPct());
                ps.setDouble(12, s.peakVolumeUsdt());
                ps.setDouble(13, s.avgVolumeUsdt());
                ps.setLong(14, s.durationMs());
                ps.setInt(15, s.tickCount());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
            log.info("Closed {} opportunity sessions", batch.size());
        } catch (SQLException e) {
            log.error("Sessions flush failed: {}", e.getMessage());
            try { connection.rollback(); } catch (SQLException ignored) {}
        }
    }

    private void flushSessionTicks() {
        List<List<OpportunityTick>> batches = new ArrayList<>();
        tickBatchBuffer.drainTo(batches);
        if (batches.isEmpty()) return;
        String sql = """
            INSERT OR IGNORE INTO session_ticks
            (session_id, seq, recorded_at, net_pct, gross_pct, max_volume_usdt, long_ask, short_bid)
            VALUES (?,?,?,?,?,?,?,?)
            """;
        int total = 0;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (List<OpportunityTick> batch : batches) {
                for (OpportunityTick t : batch) {
                    ps.setString(1, t.sessionId());
                    ps.setInt(2, t.seq());
                    ps.setString(3, t.recordedAt().toString());
                    ps.setDouble(4, t.netPct());
                    ps.setDouble(5, t.grossPct());
                    ps.setDouble(6, t.maxVolumeUsdt());
                    ps.setDouble(7, t.longAsk());
                    ps.setDouble(8, t.shortBid());
                    ps.addBatch();
                    total++;
                }
            }
            ps.executeBatch();
            connection.commit();
            log.debug("Flushed {} session ticks to SQLite", total);
        } catch (SQLException e) {
            log.error("Session ticks flush failed: {}", e.getMessage());
            try { connection.rollback(); } catch (SQLException ignored) {}
        }
    }

    private void flushSilent() {
        try { flush(); } catch (Exception e) { log.error("Scheduled flush error", e); }
    }

    public synchronized OpportunityStats queryStats() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                "SELECT COUNT(*), AVG(net_spread_pct), MAX(net_spread_pct), MIN(detected_at), MAX(detected_at) FROM opportunities");
            long total = rs.getLong(1);
            double avg = rs.getDouble(2);
            double max = rs.getDouble(3);
            String first = rs.getString(4);
            String last = rs.getString(5);

            Map<String, Long> bySymbol = new LinkedHashMap<>();
            ResultSet rs2 = stmt.executeQuery(
                "SELECT canonical_symbol, COUNT(*) FROM opportunities GROUP BY canonical_symbol ORDER BY COUNT(*) DESC");
            while (rs2.next()) bySymbol.put(rs2.getString(1), rs2.getLong(2));

            Map<String, Long> byPair = new LinkedHashMap<>();
            ResultSet rs3 = stmt.executeQuery(
                "SELECT long_exchange || '->' || short_exchange, COUNT(*) FROM opportunities GROUP BY long_exchange, short_exchange ORDER BY COUNT(*) DESC");
            while (rs3.next()) byPair.put(rs3.getString(1), rs3.getLong(2));

            return new OpportunityStats(total, avg, max, bySymbol, byPair,
                first != null ? java.time.Instant.parse(first) : null,
                last != null ? java.time.Instant.parse(last) : null);
        }
    }

    public synchronized List<com.arbbot.scanner.Opportunity> queryRecent(int limit) throws SQLException {
        List<com.arbbot.scanner.Opportunity> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, canonical_symbol, long_exchange, long_ask_price, long_best_bid,"
                + " short_exchange, short_bid_price, short_best_ask,"
                + " gross_spread_pct, net_spread_pct, estimated_cost_pct,"
                + " max_volume_usdt, long_ask_depth_usdt, short_bid_depth_usdt, detected_at"
                + " FROM opportunities ORDER BY detected_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new com.arbbot.scanner.Opportunity(
                    java.util.UUID.fromString(rs.getString(1)),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getDouble(4),
                    rs.getDouble(5),
                    rs.getString(6),
                    rs.getDouble(7),
                    rs.getDouble(8),
                    rs.getDouble(9),
                    rs.getDouble(10),
                    rs.getDouble(11),
                    null, null,
                    0.0,
                    rs.getDouble(12),
                    rs.getDouble(13),
                    rs.getDouble(14),
                    java.time.Instant.parse(rs.getString(15))));
            }
        }
        return result;
    }

    public synchronized List<OpportunitySession> queryRecentSessions(int limit) throws SQLException {
        List<OpportunitySession> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, canonical_symbol, long_exchange, short_exchange, started_at, ended_at,"
                + " peak_net_pct, avg_net_pct, min_net_pct, entry_net_pct, exit_net_pct,"
                + " peak_volume_usdt, avg_volume_usdt, duration_ms, tick_count"
                + " FROM opportunity_sessions ORDER BY ended_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new OpportunitySession(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    java.time.Instant.parse(rs.getString(5)),
                    java.time.Instant.parse(rs.getString(6)),
                    rs.getDouble(7), rs.getDouble(8), rs.getDouble(9),
                    rs.getDouble(10), rs.getDouble(11),
                    rs.getDouble(12), rs.getDouble(13),
                    rs.getLong(14), rs.getInt(15)));
            }
        }
        return result;
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdown();
            try { scheduler.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        flush();
        try { if (connection != null) connection.close(); } catch (SQLException e) { log.error("Close error", e); }
    }
}
