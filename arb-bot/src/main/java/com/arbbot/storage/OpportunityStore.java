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
        long durationMs,
        int tickCount
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
                    if (!s.isBlank()) stmt.execute(s.trim());
                }
            }
            connection.commit();
        } catch (Exception e) {
            throw new SQLException("Schema init failed", e);
        }
    }

    public void save(Opportunity opp) {
        buffer.offer(opp);
    }

    public void saveSession(OpportunitySession session) {
        sessionBuffer.offer(session);
    }

    public synchronized void flush() {
        flushOpportunities();
        flushSessions();
    }

    private void flushOpportunities() {
        List<Opportunity> batch = new ArrayList<>();
        buffer.drainTo(batch);
        if (batch.isEmpty()) return;
        String sql = """
            INSERT OR IGNORE INTO opportunities
            (id, canonical_symbol, long_exchange, long_ask_price, short_exchange, short_bid_price,
             gross_spread_pct, net_spread_pct, estimated_cost_pct, long_funding_rate, short_funding_rate,
             long_next_funding, short_next_funding, order_size_usdt, detected_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (Opportunity o : batch) {
                ps.setString(1, o.id().toString());
                ps.setString(2, o.canonicalSymbol());
                ps.setString(3, o.longExchange());
                ps.setDouble(4, o.longExchangeAskPrice());
                ps.setString(5, o.shortExchange());
                ps.setDouble(6, o.shortExchangeBidPrice());
                ps.setDouble(7, o.grossSpreadPct());
                ps.setDouble(8, o.netSpreadPct());
                ps.setDouble(9, o.estimatedTotalCostPct());
                ps.setDouble(10, o.longExchangeFunding() != null ? o.longExchangeFunding().currentRate() : 0);
                ps.setDouble(11, o.shortExchangeFunding() != null ? o.shortExchangeFunding().currentRate() : 0);
                ps.setString(12, o.longExchangeFunding() != null ? o.longExchangeFunding().nextSettlement().toString() : null);
                ps.setString(13, o.shortExchangeFunding() != null ? o.shortExchangeFunding().nextSettlement().toString() : null);
                ps.setDouble(14, o.orderSizeUsdt());
                ps.setString(15, o.detectedAt().toString());
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
             started_at, ended_at, peak_net_pct, avg_net_pct, duration_ms, tick_count)
            VALUES (?,?,?,?,?,?,?,?,?,?)
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
                ps.setLong(9, s.durationMs());
                ps.setInt(10, s.tickCount());
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
                "SELECT canonical_symbol, COUNT(*) FROM opportunities GROUP BY canonical_symbol");
            while (rs2.next()) bySymbol.put(rs2.getString(1), rs2.getLong(2));

            Map<String, Long> byPair = new LinkedHashMap<>();
            ResultSet rs3 = stmt.executeQuery(
                "SELECT long_exchange || '->' || short_exchange, COUNT(*) FROM opportunities GROUP BY long_exchange, short_exchange");
            while (rs3.next()) byPair.put(rs3.getString(1), rs3.getLong(2));

            return new OpportunityStats(total, avg, max, bySymbol, byPair,
                first != null ? java.time.Instant.parse(first) : null,
                last != null ? java.time.Instant.parse(last) : null);
        }
    }

    public synchronized List<com.arbbot.scanner.Opportunity> queryRecent(int limit) throws SQLException {
        List<com.arbbot.scanner.Opportunity> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, canonical_symbol, long_exchange, long_ask_price, short_exchange,"
                    + " short_bid_price, gross_spread_pct, net_spread_pct, estimated_cost_pct,"
                    + " detected_at FROM opportunities ORDER BY detected_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new com.arbbot.scanner.Opportunity(
                    java.util.UUID.fromString(rs.getString(1)),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getDouble(4),
                    rs.getString(5),
                    rs.getDouble(6),
                    rs.getDouble(7),
                    rs.getDouble(8),
                    rs.getDouble(9),
                    null, null,
                    0.0,
                    java.time.Instant.parse(rs.getString(10))));
            }
        }
        return result;
    }

    public synchronized List<OpportunitySession> queryRecentSessions(int limit) throws SQLException {
        List<OpportunitySession> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, canonical_symbol, long_exchange, short_exchange, started_at, ended_at,"
                + " peak_net_pct, avg_net_pct, duration_ms, tick_count"
                + " FROM opportunity_sessions ORDER BY ended_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new OpportunitySession(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    java.time.Instant.parse(rs.getString(5)),
                    java.time.Instant.parse(rs.getString(6)),
                    rs.getDouble(7), rs.getDouble(8),
                    rs.getLong(9), rs.getInt(10)));
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
