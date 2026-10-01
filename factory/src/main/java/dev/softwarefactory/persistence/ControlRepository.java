package dev.softwarefactory.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.softwarefactory.execution.InfrastructureException;
import dev.softwarefactory.serialization.Json;
import dev.softwarefactory.workflow.RunState;
import dev.softwarefactory.workflow.WorkflowConflictException;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Separate PostgreSQL authority for state and a keyed, chained audit log. */
public final class ControlRepository implements RunStore {
    /** Dashboards and metrics sample the most recently updated runs. */
    public static final int RECENT_RUNS = 100;
    private static final int RECENT_EVENTS = 200;
    private static final int MAX_CHAT_AUDIT_DETAIL = 2000;
    private static final int MAX_CHAT_BUDGET = 10_000;
    /** First key of the two-int advisory lock form; isolates run leases from other advisory lock users. */
    static final int LEASE_NAMESPACE = 0x46414354; // "FACT"
    private static final Logger LOG = LoggerFactory.getLogger(ControlRepository.class);
    private final DataSource dataSource;
    private final AuditChain chain;

    public ControlRepository(String url, String user, String password, AuditKey key) {
        var source = new DriverManagerDataSource(url, user, password);
        var properties = new Properties();
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "30");
        properties.setProperty("options", "-c statement_timeout=15000 -c lock_timeout=5000");
        source.setConnectionProperties(properties);
        this.dataSource = source;
        this.chain = new AuditChain(key);
    }

    public ControlRepository(DataSource dataSource, AuditKey key) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.chain = new AuditChain(key);
    }

    /** CLI and isolated integration fixtures use the same versioned schema as Boot. */
    public void initialize() {
        var configuration = Flyway.configure().locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0");
        configuration.dataSource(dataSource);
        configuration.load().migrate();
    }

    public static final class RunNotFound extends RunStore.MissingRunException {
        public RunNotFound(String id) { super("Run not found: " + id); }
    }

    public record AuditEvent(long sequence, Instant at, String type, String detail) {}

    @FunctionalInterface private interface Work<T> { T run(Connection connection) throws SQLException, IOException; }

    private <T> T withConnection(Work<T> work) throws IOException {
        try (Connection connection = dataSource.getConnection()) {
            return work.run(connection);
        } catch (SQLException failure) {
            throw new InfrastructureException("Control database operation failed: " + failure.getMessage(), failure);
        }
    }

    @Override public RunState load(String id) throws IOException {
        UUID run = UUID.fromString(id);
        return withConnection(connection -> {
            try (var query = connection.prepareStatement("SELECT state_json, revision FROM runs WHERE id = ?")) {
                query.setObject(1, run);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) throw new RunNotFound(id);
                    RunState state = Json.MAPPER.readValue(rows.getString(1), RunState.class);
                    state.revision = rows.getLong(2);
                    return state;
                }
            }
        });
    }

    public List<RunState> recentRuns() throws IOException {
        return withConnection(connection -> {
            List<RunState> result = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT state_json FROM runs ORDER BY updated_at DESC LIMIT ?")) {
                query.setInt(1, RECENT_RUNS);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) result.add(Json.MAPPER.readValue(rows.getString(1), RunState.class));
                }
            }
            return result;
        });
    }

    /** Runs last updated before {@code cutoff}; terminal runs are not updated after they finish. */
    public List<RunState> runsUpdatedBefore(Instant cutoff) throws IOException {
        return withConnection(connection -> {
            List<RunState> result = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT state_json FROM runs WHERE updated_at < ? ORDER BY updated_at")) {
                query.setObject(1, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
                try (var rows = query.executeQuery()) {
                    while (rows.next()) result.add(Json.MAPPER.readValue(rows.getString(1), RunState.class));
                }
            }
            return result;
        });
    }

    public List<Map<String, Object>> events(String id) throws IOException {
        UUID run = UUID.fromString(id);
        return withConnection(connection -> {
            List<Map<String, Object>> result = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT seq, at, type, detail FROM audit_events WHERE run_id = ? ORDER BY seq DESC LIMIT ?")) {
                query.setObject(1, run);
                query.setInt(2, RECENT_EVENTS);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        result.add(Map.of("sequence", rows.getLong(1), "at", rows.getString(2), "type", rows.getString(3), "detail", rows.getString(4)));
                    }
                }
            }
            return result;
        });
    }

    public List<AuditEvent> timeline(String id) throws IOException {
        UUID run = UUID.fromString(id);
        return withConnection(connection -> {
            List<AuditEvent> result = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT seq, at, type, detail FROM audit_events WHERE run_id = ? ORDER BY seq")) {
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
        return withConnection(connection -> {
            Map<String, List<AuditEvent>> result = new LinkedHashMap<>();
            try (var query = connection.prepareStatement("""
                    SELECT recent.id, e.seq, e.at, e.type, e.detail
                    FROM (SELECT id, updated_at FROM runs ORDER BY updated_at DESC LIMIT ?) recent
                    LEFT JOIN audit_events e ON e.run_id = recent.id AND e.type = ANY (?)
                    ORDER BY recent.updated_at DESC, recent.id, e.seq""")) {
                query.setInt(1, RECENT_RUNS);
                query.setArray(2, connection.createArrayOf("text", types.toArray()));
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        List<AuditEvent> events = result.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>());
                        if (rows.getObject(2) != null) events.add(event(rows, 2));
                    }
                }
            }
            return result;
        });
    }

    private static AuditEvent event(ResultSet rows, int first) throws SQLException {
        return new AuditEvent(rows.getLong(first), rows.getObject(first + 1, OffsetDateTime.class).toInstant(),
            rows.getString(first + 2), rows.getString(first + 3));
    }

    /** Holds an exclusive PostgreSQL advisory lock for one operator transition. */
    @Override public RunLease lease(String id) throws IOException {
        UUID run = UUID.fromString(id);
        int key = run.hashCode();
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException unavailable) {
            throw new InfrastructureException("Control database unavailable", unavailable);
        }
        boolean acquired;
        try (var query = connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
            query.setInt(1, LEASE_NAMESPACE);
            query.setInt(2, key);
            try (var row = query.executeQuery()) {
                acquired = row.next() && row.getBoolean(1);
            }
        } catch (SQLException failure) {
            // The lock may have been granted before the transport failed: never pool that session.
            discard(connection, failure);
            throw new InfrastructureException("Could not acquire run lease", failure);
        }
        if (!acquired) {
            var conflict = new WorkflowConflictException("Run is already being advanced: " + id);
            try {
                connection.close();
            } catch (SQLException cleanup) {
                conflict.addSuppressed(cleanup);
            }
            throw conflict;
        }
        return new RunLease(connection, key);
    }

    private static void discard(Connection connection, Exception failure) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException abort) {
            failure.addSuppressed(abort);
        }
        try {
            connection.close();
        } catch (SQLException close) {
            failure.addSuppressed(close);
        }
    }

    public record RunLease(Connection connection, int key) implements RunStore.Lease {
        @Override public void close() throws IOException {
            boolean released;
            try (var unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
                unlock.setInt(1, LEASE_NAMESPACE);
                unlock.setInt(2, key);
                try (var row = unlock.executeQuery()) {
                    released = row.next() && row.getBoolean(1);
                }
            } catch (SQLException failure) {
                // A session with an uncertain lock must never return to the pool.
                discard(connection, failure);
                throw new InfrastructureException("Could not release run lease", failure);
            }
            if (!released) {
                LOG.error("Run lease {}/{} was not held by its session at release; leases may not be exclusive", LEASE_NAMESPACE, key);
            }
            try {
                connection.close();
            } catch (SQLException failure) {
                throw new InfrastructureException("Could not return lease connection", failure);
            }
        }
    }

    @Override public void record(RunState state, String type, String detail) throws IOException {
        synchronized (state) { recordSnapshot(state, type, detail); }
    }

    private void recordSnapshot(RunState state, String type, String detail) throws IOException {
        ObjectNode snapshot = Json.MAPPER.valueToTree(state);
        snapshot.put("revision", state.revision + 1);
        String stateJson = Json.MAPPER.writeValueAsString(snapshot);
        UUID run = UUID.fromString(state.id);
        withConnection(connection -> {
            connection.setAutoCommit(false);
            try {
                lockOrCreate(connection, state, run);
                String previous = AuditChain.GENESIS;
                long sequence = 1;
                try (var last = connection.prepareStatement("SELECT seq, event_hash FROM audit_events WHERE run_id = ? ORDER BY seq DESC LIMIT 1")) {
                    last.setObject(1, run);
                    try (var rows = last.executeQuery()) {
                        if (rows.next()) {
                            sequence = rows.getLong(1) + 1;
                            previous = rows.getString(2);
                        }
                    }
                }
                Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
                String hash = chain.hash(AuditChain.HMAC_SHA256, previous, sequence, at, type, detail, stateJson);
                try (var event = connection.prepareStatement("INSERT INTO audit_events(run_id, seq, at, type, detail, previous_hash, event_hash, state_json, hash_scheme) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    event.setObject(1, run);
                    event.setLong(2, sequence);
                    event.setObject(3, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
                    event.setString(4, type);
                    event.setString(5, detail);
                    event.setString(6, previous);
                    event.setString(7, hash);
                    event.setString(8, stateJson);
                    event.setInt(9, AuditChain.HMAC_SHA256);
                    event.executeUpdate();
                }
                try (var update = connection.prepareStatement("UPDATE runs SET state_json = ?, updated_at = ?, revision = revision + 1 WHERE id = ?")) {
                    update.setString(1, stateJson);
                    update.setObject(2, OffsetDateTime.now());
                    update.setObject(3, run);
                    update.executeUpdate();
                }
                connection.commit();
                state.revision++;
                return null;
            } catch (SQLException | IOException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        });
    }

    private static void lockOrCreate(Connection connection, RunState state, UUID run) throws SQLException, IOException {
        try (var lock = connection.prepareStatement("SELECT revision FROM runs WHERE id = ? FOR UPDATE")) {
            lock.setObject(1, run);
            try (var current = lock.executeQuery()) {
                if (current.next()) {
                    if (current.getLong(1) != state.revision) throw new WorkflowConflictException("Stale run revision; reload before retrying");
                    return;
                }
            }
        }
        if (state.revision != 0) throw new WorkflowConflictException("Run no longer exists");
        try (var insert = connection.prepareStatement("INSERT INTO runs(id, state_json, updated_at) VALUES (?, ?, ?)")) {
            insert.setObject(1, run);
            insert.setString(2, Json.MAPPER.writeValueAsString(state));
            insert.setObject(3, OffsetDateTime.now());
            insert.executeUpdate();
        }
    }

    private static void rollback(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollback) {
            failure.addSuppressed(rollback);
        }
    }

    @Override public boolean auditValid(String id) throws IOException {
        UUID run = UUID.fromString(id);
        return withConnection(connection -> {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                String latestState = verifyChain(connection, run);
                if (latestState == null) return false;
                try (var current = connection.prepareStatement("SELECT state_json FROM runs WHERE id = ?")) {
                    current.setObject(1, run);
                    try (var row = current.executeQuery()) {
                        return row.next() && (latestState.isEmpty() || latestState.equals(row.getString(1)));
                    }
                }
            } finally {
                connection.rollback();
            }
        });
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
                    if (rowScheme < scheme || (rowScheme != AuditChain.LEGACY_SHA256 && rowScheme != AuditChain.HMAC_SHA256)) return null;
                    scheme = rowScheme;
                    Instant at = rows.getObject(2, OffsetDateTime.class).toInstant();
                    String state = rows.getString(7);
                    String calculated = chain.hash(scheme, previous, expected, at, rows.getString(3), rows.getString(4), state);
                    if (rows.getLong(1) != expected || !AuditChain.matches(previous, rows.getString(5))
                        || !AuditChain.matches(calculated, rows.getString(6))) return null;
                    latestState = state == null ? "" : state;
                    previous = calculated;
                    expected++;
                }
                return latestState;
            }
        }
    }

    /** Increments the persistent daily budget and audits the reservation in one transaction. */
    public void reserveChatRequest(int limit) throws IOException {
        if (limit < 1 || limit > MAX_CHAT_BUDGET) throw new IllegalArgumentException("Chat request budget must be 1.." + MAX_CHAT_BUDGET);
        withConnection(connection -> {
            connection.setAutoCommit(false);
            try {
                try (var query = connection.prepareStatement(
                        "INSERT INTO chat_budget(day, requests) VALUES (?, 1) ON CONFLICT(day) DO UPDATE SET requests = chat_budget.requests + 1 WHERE chat_budget.requests < ? RETURNING requests")) {
                    query.setObject(1, LocalDate.now(ZoneOffset.UTC));
                    query.setInt(2, limit);
                    try (var row = query.executeQuery()) {
                        if (!row.next()) throw new WorkflowConflictException("Daily chat provider-request budget exhausted");
                    }
                }
                insertChatAudit(connection, "MODEL_CALL_RESERVED", "dailyLimit=" + limit);
                connection.commit();
                return null;
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        });
    }

    public void chatAudit(String type, String detail) throws IOException {
        withConnection(connection -> {
            insertChatAudit(connection, type, detail);
            return null;
        });
    }

    private static void insertChatAudit(Connection connection, String type, String detail) throws SQLException {
        try (var query = connection.prepareStatement("INSERT INTO chat_audit(id, at, type, detail) VALUES (?, ?, ?, ?)")) {
            query.setObject(1, UUID.randomUUID());
            query.setObject(2, OffsetDateTime.now());
            query.setString(3, type);
            query.setString(4, detail.substring(0, Math.min(detail.length(), MAX_CHAT_AUDIT_DETAIL)));
            query.executeUpdate();
        }
    }
}
