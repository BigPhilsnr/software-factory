package dev.softwarefactory.persistence;

import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.serialization.Json;
import dev.softwarefactory.workflow.RunState;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;

/** Separate PostgreSQL authority for state and a chained audit log. */
public final class ControlRepository implements RunStore {
    private final String url;
    private final String user;
    private final String password;

    public ControlRepository(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public void initialize() throws SQLException {
        try (Connection connection = connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS runs (id UUID PRIMARY KEY, state_json TEXT NOT NULL, updated_at TIMESTAMPTZ NOT NULL)");
            statement.execute("ALTER TABLE runs ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 0");
            statement.execute("CREATE TABLE IF NOT EXISTS audit_events (run_id UUID NOT NULL REFERENCES runs(id), seq BIGINT NOT NULL, at TIMESTAMPTZ NOT NULL, type TEXT NOT NULL, detail TEXT NOT NULL, previous_hash CHAR(64) NOT NULL, event_hash CHAR(64) NOT NULL, PRIMARY KEY(run_id, seq))");
            statement.execute("ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS state_json TEXT");
            statement.execute("CREATE TABLE IF NOT EXISTS chat_budget (day DATE PRIMARY KEY, requests INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS chat_audit (id UUID PRIMARY KEY, at TIMESTAMPTZ NOT NULL, type TEXT NOT NULL, detail TEXT NOT NULL)");
        }
    }

    public static final class RunNotFound extends RunStore.MissingRunException {
        public RunNotFound(String id) { super("Run not found: " + id); }
    }

    @Override public RunState load(String id) throws Exception {
        try (Connection connection = connect(); var query = connection.prepareStatement("SELECT state_json, revision FROM runs WHERE id = ?")) {
            query.setObject(1, java.util.UUID.fromString(id));
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new RunNotFound(id);
                RunState state = Json.MAPPER.readValue(rows.getString(1), RunState.class);
                state.revision = rows.getLong(2);
                return state;
            }
        }
    }

    public java.util.List<RunState> recentRuns() throws Exception {
        java.util.List<RunState> result = new java.util.ArrayList<>();
        try (Connection connection = connect(); var query = connection.prepareStatement("SELECT state_json FROM runs ORDER BY updated_at DESC LIMIT 100"); var rows = query.executeQuery()) {
            while (rows.next()) result.add(Json.MAPPER.readValue(rows.getString(1), RunState.class));
        }
        return result;
    }

    public java.util.List<java.util.Map<String, Object>> events(String id) throws Exception {
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        try (Connection connection = connect(); var query = connection.prepareStatement("SELECT seq, at, type, detail FROM audit_events WHERE run_id = ? ORDER BY seq DESC LIMIT 200")) {
            query.setObject(1, java.util.UUID.fromString(id));
            try (var rows = query.executeQuery()) {
                while (rows.next()) result.add(java.util.Map.of("sequence", rows.getLong(1), "at", rows.getString(2), "type", rows.getString(3), "detail", rows.getString(4)));
            }
        }
        return result;
    }

    public record AuditEvent(long sequence, Instant at, String type, String detail) {}

    public java.util.List<AuditEvent> timeline(String id) throws Exception {
        java.util.List<AuditEvent> result = new java.util.ArrayList<>();
        try (Connection connection = connect(); var query = connection.prepareStatement(
                "SELECT seq, at, type, detail FROM audit_events WHERE run_id = ? ORDER BY seq")) {
            query.setObject(1, java.util.UUID.fromString(id));
            try (var rows = query.executeQuery()) {
                while (rows.next()) result.add(new AuditEvent(rows.getLong(1),
                    rows.getObject(2, java.time.OffsetDateTime.class).toInstant(), rows.getString(3), rows.getString(4)));
            }
        }
        return result;
    }

    /** Holds an exclusive PostgreSQL advisory lock for one operator transition. */
    @Override public RunLease lease(String id) throws Exception {
        java.util.UUID run = java.util.UUID.fromString(id);
        Connection connection = connect();
        long key = run.getMostSignificantBits() ^ run.getLeastSignificantBits();
        try (var query = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            query.setLong(1, key);
            try (var row = query.executeQuery()) {
                row.next();
                if (!row.getBoolean(1)) throw new IllegalStateException("Run is already being advanced: " + id);
            }
            return new RunLease(connection);
        } catch (Exception failure) {
            connection.close();
            throw failure;
        }
    }

    public record RunLease(Connection connection) implements AutoCloseable {
        @Override public void close() throws SQLException { connection.close(); }
    }

    @Override public void record(RunState state, String type, String detail) throws Exception {
        synchronized (state) { recordSnapshot(state, type, detail); }
    }

    private void recordSnapshot(RunState state, String type, String detail) throws Exception {
        var snapshot = Json.MAPPER.valueToTree(state);
        ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot).put("revision", state.revision + 1);
        String stateJson = Json.MAPPER.writeValueAsString(snapshot);
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                String previous = "0".repeat(64);
                long sequence = 1;
                try (var lock = connection.prepareStatement("SELECT revision FROM runs WHERE id = ? FOR UPDATE")) {
                    lock.setObject(1, java.util.UUID.fromString(state.id));
                    try (var ignored = lock.executeQuery()) {
                        if (ignored.next()) {
                            if (ignored.getLong(1) != state.revision) throw new IllegalStateException("Stale run revision; reload before retrying");
                        } else {
                            if (state.revision != 0) throw new IllegalStateException("Run no longer exists");
                            try (var insert = connection.prepareStatement("INSERT INTO runs(id, state_json, updated_at) VALUES (?, ?, ?)")) {
                                insert.setObject(1, java.util.UUID.fromString(state.id));
                                insert.setString(2, Json.MAPPER.writeValueAsString(state));
                                insert.setObject(3, java.time.OffsetDateTime.now());
                                insert.executeUpdate();
                            }
                        }
                    }
                }
                try (var last = connection.prepareStatement("SELECT seq, event_hash FROM audit_events WHERE run_id = ? ORDER BY seq DESC LIMIT 1")) {
                    last.setObject(1, java.util.UUID.fromString(state.id));
                    try (var rows = last.executeQuery()) {
                        if (rows.next()) { sequence = rows.getLong(1) + 1; previous = rows.getString(2); }
                    }
                }
                Instant at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                String hash = Hashes.sha256(previous + "|" + sequence + "|" + at + "|" + type + "|" + detail + "|state=" + stateJson);
                try (var event = connection.prepareStatement("INSERT INTO audit_events(run_id, seq, at, type, detail, previous_hash, event_hash, state_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    event.setObject(1, java.util.UUID.fromString(state.id));
                    event.setLong(2, sequence);
                    event.setObject(3, java.time.OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC));
                    event.setString(4, type);
                    event.setString(5, detail);
                    event.setString(6, previous);
                    event.setString(7, hash);
                    event.setString(8, stateJson);
                    event.executeUpdate();
                }
                try (var update = connection.prepareStatement("UPDATE runs SET state_json = ?, updated_at = ?, revision = revision + 1 WHERE id = ?")) {
                    update.setString(1, stateJson);
                    update.setObject(2, java.time.OffsetDateTime.now());
                    update.setObject(3, java.util.UUID.fromString(state.id));
                    update.executeUpdate();
                }
                connection.commit();
                state.revision++;
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    @Override public boolean auditValid(String id) throws Exception {
        try (Connection connection = connect(); var query = connection.prepareStatement("SELECT seq, at, type, detail, previous_hash, event_hash, state_json FROM audit_events WHERE run_id = ? ORDER BY seq")) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            query.setObject(1, java.util.UUID.fromString(id));
            try (var rows = query.executeQuery()) {
                String previous = "0".repeat(64);
                long expected = 1;
                String latestState = null;
                while (rows.next()) {
                    String at = rows.getObject(2, java.time.OffsetDateTime.class).toInstant().toString();
                    latestState = rows.getString(7);
                    String calculated = Hashes.sha256(previous + "|" + expected + "|" + at + "|" + rows.getString(3) + "|" + rows.getString(4)
                        + (latestState == null ? "" : "|state=" + latestState));
                    if (rows.getLong(1) != expected || !rows.getString(5).equals(previous) || !rows.getString(6).equals(calculated)) return false;
                    previous = calculated;
                    expected++;
                }
                if (latestState != null) {
                    try (var current = connection.prepareStatement("SELECT state_json FROM runs WHERE id = ?")) {
                        current.setObject(1, java.util.UUID.fromString(id));
                        try (var row = current.executeQuery()) { if (!row.next() || !latestState.equals(row.getString(1))) return false; }
                    }
                }
                return expected > 1;
            }
        }
    }

    public void reserveChatRequest(int limit) throws SQLException {
        if (limit < 1 || limit > 10000) throw new IllegalArgumentException("Chat request budget must be 1..10000");
        try (var connection = connect(); var query = connection.prepareStatement(
                "INSERT INTO chat_budget(day, requests) VALUES (?, 1) ON CONFLICT(day) DO UPDATE SET requests = chat_budget.requests + 1 WHERE chat_budget.requests < ? RETURNING requests")) {
            query.setObject(1, java.time.LocalDate.now(java.time.ZoneOffset.UTC));
            query.setInt(2, limit);
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("Daily chat provider-request budget exhausted");
            }
        }
        chatAudit("MODEL_CALL_RESERVED", "dailyLimit=" + limit);
    }

    public void chatAudit(String type, String detail) throws SQLException {
        try (var connection = connect(); var query = connection.prepareStatement("INSERT INTO chat_audit VALUES (?, ?, ?, ?)")) {
            query.setObject(1, java.util.UUID.randomUUID());
            query.setObject(2, java.time.OffsetDateTime.now());
            query.setString(3, type);
            query.setString(4, detail.substring(0, Math.min(detail.length(), 2000)));
            query.executeUpdate();
        }
    }

    private Connection connect() throws SQLException { var properties = new java.util.Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "30");
        properties.setProperty("options", "-c statement_timeout=15000 -c lock_timeout=5000");
        return DriverManager.getConnection(url, properties); }
}
