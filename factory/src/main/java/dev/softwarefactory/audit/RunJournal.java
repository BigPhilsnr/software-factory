package dev.softwarefactory.audit;

import dev.softwarefactory.platform.WorkflowConflictException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Authoritative run snapshots. A snapshot only changes together with the audit event that explains it,
 * in one transaction, and only when the writer saw the latest revision.
 */
public final class RunJournal {
    /** Dashboards and metrics sample the most recently updated runs. */
    public static final int RECENT_RUNS = 100;

    private final ControlDatabase database;
    private final AuditChain chain;

    public RunJournal(ControlDatabase database, AuditKey key) {
        this.database = database;
        this.chain = new AuditChain(key);
    }

    /** A run's serialized state and the revision a writer must present to replace it. */
    public record Snapshot(String stateJson, long revision) {}

    public Optional<Snapshot> find(UUID run) throws IOException {
        return database.query(connection -> {
            try (var query = connection.prepareStatement("SELECT state_json, revision FROM runs WHERE id = ?")) {
                query.setObject(1, run);
                try (var rows = query.executeQuery()) {
                    return rows.next()
                            ? Optional.of(new Snapshot(rows.getString(1), rows.getLong(2)))
                            : Optional.empty();
                }
            }
        });
    }

    public List<String> recentStates() throws IOException {
        return database.query(connection -> {
            try (var query =
                    connection.prepareStatement("SELECT state_json FROM runs ORDER BY updated_at DESC LIMIT ?")) {
                query.setInt(1, RECENT_RUNS);
                return states(query);
            }
        });
    }

    /** Runs last updated before {@code cutoff}; terminal runs are not updated after they finish. */
    public List<String> statesUpdatedBefore(Instant cutoff) throws IOException {
        return database.query(connection -> {
            try (var query = connection.prepareStatement(
                    "SELECT state_json FROM runs WHERE updated_at < ? ORDER BY updated_at")) {
                query.setObject(1, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
                return states(query);
            }
        });
    }

    /**
     * Replaces the run's snapshot and appends the event that explains the change.
     *
     * @param expectedRevision the revision the writer loaded; 0 creates the run
     * @throws WorkflowConflictException when another writer changed (or removed) the run first
     */
    public void record(UUID run, long expectedRevision, String stateJson, String type, String detail)
            throws IOException {
        database.transaction(connection -> {
            lockOrCreate(connection, run, expectedRevision, stateJson);
            AuditTrail.Head head = AuditTrail.head(connection, run);
            Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
            long sequence = head.sequence() + 1;
            String hash = chain.hash(AuditChain.HMAC_SHA256, head.hash(), sequence, at, type, detail, stateJson);
            try (var event = connection.prepareStatement(
                    "INSERT INTO audit_events(run_id, seq, at, type, detail, previous_hash, event_hash, state_json, hash_scheme) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                event.setObject(1, run);
                event.setLong(2, sequence);
                event.setObject(3, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
                event.setString(4, type);
                event.setString(5, detail);
                event.setString(6, head.hash());
                event.setString(7, hash);
                event.setString(8, stateJson);
                event.setInt(9, AuditChain.HMAC_SHA256);
                event.executeUpdate();
            }
            try (var update = connection.prepareStatement(
                    "UPDATE runs SET state_json = ?, updated_at = ?, revision = revision + 1 WHERE id = ?")) {
                update.setString(1, stateJson);
                update.setObject(2, OffsetDateTime.now());
                update.setObject(3, run);
                update.executeUpdate();
            }
            return null;
        });
    }

    private static List<String> states(java.sql.PreparedStatement query) throws SQLException {
        List<String> result = new ArrayList<>();
        try (var rows = query.executeQuery()) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    private static void lockOrCreate(Connection connection, UUID run, long expectedRevision, String stateJson)
            throws SQLException {
        try (var lock = connection.prepareStatement("SELECT revision FROM runs WHERE id = ? FOR UPDATE")) {
            lock.setObject(1, run);
            try (var current = lock.executeQuery()) {
                if (current.next()) {
                    if (current.getLong(1) != expectedRevision)
                        throw new WorkflowConflictException("Stale run revision; reload before retrying");
                    return;
                }
            }
        }
        if (expectedRevision != 0) throw new WorkflowConflictException("Run no longer exists");
        try (var insert =
                connection.prepareStatement("INSERT INTO runs(id, state_json, updated_at) VALUES (?, ?, ?)")) {
            insert.setObject(1, run);
            insert.setString(2, stateJson);
            insert.setObject(3, OffsetDateTime.now());
            insert.executeUpdate();
        }
    }
}
