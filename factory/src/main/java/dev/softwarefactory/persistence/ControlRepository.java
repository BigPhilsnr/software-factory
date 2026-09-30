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
            statement.execute("CREATE TABLE IF NOT EXISTS audit_events (run_id UUID NOT NULL REFERENCES runs(id), seq BIGINT NOT NULL, at TIMESTAMPTZ NOT NULL, type TEXT NOT NULL, detail TEXT NOT NULL, previous_hash CHAR(64) NOT NULL, event_hash CHAR(64) NOT NULL, PRIMARY KEY(run_id, seq))");
        }
    }

    @Override public RunState load(String id) throws Exception {
        try (Connection connection = connect(); var query = connection.prepareStatement("SELECT state_json FROM runs WHERE id = ?")) {
            query.setObject(1, java.util.UUID.fromString(id));
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Run not found: " + id);
                return Json.MAPPER.readValue(rows.getString(1), RunState.class);
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
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                String previous = "0".repeat(64);
                long sequence = 1;
                try (var lock = connection.prepareStatement("SELECT id FROM runs WHERE id = ? FOR UPDATE")) {
                    lock.setObject(1, java.util.UUID.fromString(state.id));
                    try (var ignored = lock.executeQuery()) {
                        if (!ignored.next()) {
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
                String hash = Hashes.sha256(previous + "|" + sequence + "|" + at + "|" + type + "|" + detail);
                try (var event = connection.prepareStatement("INSERT INTO audit_events(run_id, seq, at, type, detail, previous_hash, event_hash) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    event.setObject(1, java.util.UUID.fromString(state.id));
                    event.setLong(2, sequence);
                    event.setObject(3, java.time.OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC));
                    event.setString(4, type);
                    event.setString(5, detail);
                    event.setString(6, previous);
                    event.setString(7, hash);
                    event.executeUpdate();
                }
                try (var update = connection.prepareStatement("UPDATE runs SET state_json = ?, updated_at = ? WHERE id = ?")) {
                    update.setString(1, Json.MAPPER.writeValueAsString(state));
                    update.setObject(2, java.time.OffsetDateTime.now());
                    update.setObject(3, java.util.UUID.fromString(state.id));
                    update.executeUpdate();
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    @Override public boolean auditValid(String id) throws Exception {
        try (Connection connection = connect(); var query = connection.prepareStatement("SELECT seq, at, type, detail, previous_hash, event_hash FROM audit_events WHERE run_id = ? ORDER BY seq")) {
            query.setObject(1, java.util.UUID.fromString(id));
            try (var rows = query.executeQuery()) {
                String previous = "0".repeat(64);
                long expected = 1;
                while (rows.next()) {
                    String at = rows.getObject(2, java.time.OffsetDateTime.class).toInstant().toString();
                    String calculated = Hashes.sha256(previous + "|" + expected + "|" + at + "|" + rows.getString(3) + "|" + rows.getString(4));
                    if (rows.getLong(1) != expected || !rows.getString(5).equals(previous) || !rows.getString(6).equals(calculated)) return false;
                    previous = calculated;
                    expected++;
                }
                return expected > 1;
            }
        }
    }

    private Connection connect() throws SQLException { return DriverManager.getConnection(url, user, password); }
}
