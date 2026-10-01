package dev.softwarefactory.audit;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads a run's audit events and verifies that neither they nor the current snapshot were rewritten. */
public final class AuditTrail {
    private static final int RECENT_EVENTS = 200;

    private final ControlDatabase database;
    private final AuditChain chain;

    public AuditTrail(ControlDatabase database, AuditKey key) {
        this.database = database;
        this.chain = new AuditChain(key);
    }

    /** The newest event of a chain: what the next event must point back to. */
    record Head(long sequence, String hash) {}

    static Head head(Connection connection, UUID run) throws SQLException {
        try (var last = connection.prepareStatement(
                "SELECT seq, event_hash FROM audit_events WHERE run_id = ? ORDER BY seq DESC LIMIT 1")) {
            last.setObject(1, run);
            try (var rows = last.executeQuery()) {
                return rows.next() ? new Head(rows.getLong(1), rows.getString(2)) : new Head(0, AuditChain.GENESIS);
            }
        }
    }

    /** The newest events first, in the shape the operator API returns. */
    public List<Map<String, Object>> recentEvents(String id) throws IOException {
        UUID run = UUID.fromString(id);
        return database.query(connection -> {
            List<Map<String, Object>> result = new ArrayList<>();
            try (var query = connection.prepareStatement(
                    "SELECT seq, at, type, detail FROM audit_events WHERE run_id = ? ORDER BY seq DESC LIMIT ?")) {
                query.setObject(1, run);
                query.setInt(2, RECENT_EVENTS);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        result.add(Map.of(
                                "sequence",
                                rows.getLong(1),
                                "at",
                                rows.getString(2),
                                "type",
                                rows.getString(3),
                                "detail",
                                rows.getString(4)));
                    }
                }
            }
            return result;
        });
    }

    public List<AuditEvent> timeline(String id) throws IOException {
        UUID run = UUID.fromString(id);
        return database.query(connection -> {
            List<AuditEvent> result = new ArrayList<>();
            try (var query = connection.prepareStatement(
                    "SELECT seq, at, type, detail FROM audit_events WHERE run_id = ? ORDER BY seq")) {
                query.setObject(1, run);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) result.add(event(rows, 1));
                }
            }
            return result;
        });
    }

    /**
     * Timelines of the most recently updated runs, restricted to the given event types, in one query.
     * Keys preserve recency order of the runs.
     */
    public Map<String, List<AuditEvent>> recentTimelines(Collection<String> types) throws IOException {
        return database.query(connection -> {
            Map<String, List<AuditEvent>> result = new LinkedHashMap<>();
            try (var query = connection.prepareStatement("""
                    SELECT recent.id, e.seq, e.at, e.type, e.detail
                    FROM (SELECT id, updated_at FROM runs ORDER BY updated_at DESC LIMIT ?) recent
                    LEFT JOIN audit_events e ON e.run_id = recent.id AND e.type = ANY (?)
                    ORDER BY recent.updated_at DESC, recent.id, e.seq""")) {
                query.setInt(1, RunJournal.RECENT_RUNS);
                query.setArray(2, connection.createArrayOf("text", types.toArray()));
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        List<AuditEvent> events =
                                result.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>());
                        if (rows.getObject(2) != null) events.add(event(rows, 2));
                    }
                }
            }
            return result;
        });
    }

    /** True when every event hash chains from genesis and the newest snapshot is the run's current state. */
    public boolean verify(String id) throws IOException {
        UUID run = UUID.fromString(id);
        return database.query(connection -> {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                String latestState = verifyChain(connection, run);
                return latestState != null && matchesCurrentState(connection, run, latestState);
            } finally {
                connection.rollback();
            }
        });
    }

    private static boolean matchesCurrentState(Connection connection, UUID run, String latestState)
            throws SQLException {
        try (var current = connection.prepareStatement("SELECT state_json FROM runs WHERE id = ?")) {
            current.setObject(1, run);
            try (var row = current.executeQuery()) {
                return row.next() && (latestState.isEmpty() || latestState.equals(row.getString(1)));
            }
        }
    }

    /**
     * @return the newest state snapshot ("" for legacy rows without one), or null when the chain is
     *     empty or invalid
     */
    private String verifyChain(Connection connection, UUID run) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT seq, at, type, detail, previous_hash, event_hash, state_json, hash_scheme FROM audit_events WHERE run_id = ? ORDER BY seq")) {
            query.setObject(1, run);
            try (var rows = query.executeQuery()) {
                String previous = AuditChain.GENESIS;
                long expected = 1;
                int scheme = AuditChain.LEGACY_SHA256;
                String latestState = null;
                while (rows.next()) {
                    int rowScheme = rows.getInt(8);
                    // Downgrading to the keyless scheme would let a database writer forge later rows.
                    if (rowScheme < scheme || !AuditChain.isKnownScheme(rowScheme)) return null;
                    scheme = rowScheme;
                    Instant at = rows.getObject(2, OffsetDateTime.class).toInstant();
                    String state = rows.getString(7);
                    String calculated =
                            chain.hash(scheme, previous, expected, at, rows.getString(3), rows.getString(4), state);
                    if (rows.getLong(1) != expected
                            || !AuditChain.matches(previous, rows.getString(5))
                            || !AuditChain.matches(calculated, rows.getString(6))) return null;
                    latestState = state == null ? "" : state;
                    previous = calculated;
                    expected++;
                }
                return latestState;
            }
        }
    }

    private static AuditEvent event(ResultSet rows, int first) throws SQLException {
        return new AuditEvent(
                rows.getLong(first),
                rows.getObject(first + 1, OffsetDateTime.class).toInstant(),
                rows.getString(first + 2),
                rows.getString(first + 3));
    }
}
