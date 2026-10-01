package dev.softwarefactory.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.platform.WorkflowConflictException;
import dev.softwarefactory.testing.IsolatedFactoryEnvironment;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class ControlRecordIntegrationTest {
    private static final String GENESIS = "0".repeat(64);
    private IsolatedFactoryEnvironment environment;

    @BeforeEach
    void schema() throws Exception {
        environment = new IsolatedFactoryEnvironment("control_test").withSchema();
    }

    @AfterEach
    void drop() throws Exception {
        environment.close();
    }

    private ControlDatabase database() {
        return ControlDatabase.open(environment.url(), environment.user(), environment.password());
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(environment.url(), environment.user(), environment.password());
    }

    @Test
    void adoptsLegacySchemaWithoutRewritingRunBytes() throws Exception {
        String original = "{  \"legacy\": true, \"timestamp\": 1.234 }";
        try (var connection = connection();
                var sql = connection.createStatement()) {
            sql.execute(
                    "CREATE TABLE runs (id UUID PRIMARY KEY, state_json TEXT NOT NULL, updated_at TIMESTAMPTZ NOT NULL)");
            try (var insert = connection.prepareStatement("INSERT INTO runs VALUES (?, ?, now())")) {
                insert.setObject(1, UUID.randomUUID());
                insert.setString(2, original);
                insert.executeUpdate();
            }
            database();
            database();
            try (var row = sql.executeQuery("SELECT state_json, revision FROM runs")) {
                assertTrue(row.next());
                assertEquals(original, row.getString(1));
                assertEquals(0, row.getLong(2));
            }
            try (var indexes = sql.executeQuery("SELECT count(*) FROM pg_indexes WHERE schemaname = '"
                    + environment.schema() + "' AND indexname IN ('runs_updated_at_idx', 'chat_audit_at_idx')")) {
                assertTrue(indexes.next());
                assertEquals(2, indexes.getInt(1));
            }
        }
    }

    @Test
    void pooledLeasesConflictWithoutLeakingOrEvictingSessions() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(environment.url());
        config.setUsername(environment.user());
        config.setPassword(environment.password());
        config.setMaximumPoolSize(2);
        try (var pool = new HikariDataSource(config)) {
            var leases = new RunLeases(ControlDatabase.using(pool));
            String run = UUID.randomUUID().toString();
            try (var lease = leases.acquire(run)) {
                assertTrue(lease.isOpen());
                // The competing lease runs on another thread and pooled session.
                var competitor = CompletableFuture.supplyAsync(() -> {
                    try (var second = leases.acquire(run)) {
                        return "acquired";
                    } catch (WorkflowConflictException expected) {
                        return "conflict";
                    } catch (Exception unexpected) {
                        return unexpected.getClass().getName();
                    }
                });
                assertEquals(
                        "conflict",
                        competitor.get(10, TimeUnit.SECONDS),
                        "Contention must surface as a conflict, not a database error");
            }
            try (var again = leases.acquire(run)) {
                assertTrue(again.isOpen(), "Released leases can be re-acquired");
            }
            try (var returned = pool.getConnection();
                    var query = returned.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
                query.setInt(1, RunLeases.NAMESPACE);
                query.setInt(2, UUID.fromString(run).hashCode());
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertFalse(row.getBoolean(1), "The recycled session must not still own the lease");
                }
            }
            assertEquals(2, pool.getHikariPoolMXBean().getTotalConnections(), "No pooled session was evicted");
        }
    }

    @Test
    void chatBudgetSurvivesRepositoryRecreationAndAuditsReservations() throws Exception {
        new ChatLedger(database()).reserveRequest(2);
        var restarted = new ChatLedger(database());
        restarted.reserveRequest(2);
        assertThrows(WorkflowConflictException.class, () -> restarted.reserveRequest(2));
        restarted.record("TOOL_STARTED", "x".repeat(5000));
        try (var connection = connection();
                var sql = connection.createStatement();
                var rows = sql.executeQuery("SELECT count(*) FROM chat_audit WHERE type='MODEL_CALL_RESERVED'")) {
            assertTrue(rows.next());
            assertEquals(2, rows.getInt(1), "A refused reservation leaves no audit row");
        }
    }

    @Test
    void rejectsStaleWritersAndBindsStateToTheKeyedAuditChain() throws Exception {
        var database = database();
        var journal = new RunJournal(database, IsolatedFactoryEnvironment.AUDIT_KEY);
        var trail = new AuditTrail(database, IsolatedFactoryEnvironment.AUDIT_KEY);
        UUID run = UUID.randomUUID();
        assertTrue(journal.find(run).isEmpty());
        assertFalse(trail.verify(run.toString()), "A run without events has no valid chain");
        journal.record(run, 0, "{\"modelCalls\":0}", "CREATED", "test");
        long stale = journal.find(run).orElseThrow().revision();
        assertEquals(1, stale);
        journal.record(run, stale, "{\"modelCalls\":1}", "UPDATED", "test");
        assertThrows(
                WorkflowConflictException.class,
                () -> journal.record(run, stale, "{\"modelCalls\":7}", "STALE", "must not overwrite"));
        assertThrows(
                WorkflowConflictException.class,
                () -> journal.record(UUID.randomUUID(), 3, "{}", "GONE", "run no longer exists"));
        assertEquals("{\"modelCalls\":1}", journal.find(run).orElseThrow().stateJson());
        assertEquals(List.of("{\"modelCalls\":1}"), journal.recentStates());
        assertEquals(List.of(), journal.statesUpdatedBefore(Instant.now().minusSeconds(3600)));
        assertEquals(
                1, journal.statesUpdatedBefore(Instant.now().plusSeconds(3600)).size());
        assertTrue(trail.verify(run.toString()));
        assertEquals(
                List.of("CREATED", "UPDATED"),
                trail.timeline(run.toString()).stream().map(AuditEvent::type).toList());
        assertEquals("UPDATED", trail.recentEvents(run.toString()).getFirst().get("type"));
        assertEquals(
                Map.of(run.toString(), List.of("UPDATED")),
                trail.recentTimelines(List.of("UPDATED")).entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().stream()
                                        .map(AuditEvent::type)
                                        .toList())));
        assertFalse(
                new AuditTrail(database, AuditKey.of("a-different-audit-key-0123456789abcdef")).verify(run.toString()),
                "Hashes cannot be verified, or recomputed, without the key");
        try (var connection = connection();
                var query = connection.prepareStatement(
                        "UPDATE runs SET state_json = replace(state_json, '\"modelCalls\":1', '\"modelCalls\":99') WHERE id=?")) {
            query.setObject(1, run);
            query.executeUpdate();
        }
        assertFalse(trail.verify(run.toString()));
    }

    @Test
    void legacyRowsStayVerifiableAndCannotBeUsedToDowngradeTheChain() throws Exception {
        var database = database();
        var journal = new RunJournal(database, IsolatedFactoryEnvironment.AUDIT_KEY);
        var trail = new AuditTrail(database, IsolatedFactoryEnvironment.AUDIT_KEY);
        UUID run = UUID.randomUUID();
        String stateJson = "{\"id\":\"" + run + "\",\"mode\":\"fixture\"}";
        try (var connection = connection()) {
            try (var insert =
                    connection.prepareStatement("INSERT INTO runs(id, state_json, updated_at) VALUES (?, ?, now())")) {
                insert.setObject(1, run);
                insert.setString(2, stateJson);
                insert.executeUpdate();
            }
            insertLegacy(connection, run, 1, GENESIS, "RUN_CREATED", stateJson);
        }
        assertTrue(trail.verify(run.toString()), "Rows written before the HMAC scheme still verify");
        journal.record(run, journal.find(run).orElseThrow().revision(), stateJson, "UPGRADED", "first keyed row");
        assertTrue(trail.verify(run.toString()));
        try (var connection = connection();
                var downgrade = connection.prepareStatement(
                        "UPDATE audit_events SET hash_scheme = 1 WHERE run_id = ? AND seq = 2")) {
            downgrade.setObject(1, run);
            downgrade.executeUpdate();
        }
        assertFalse(trail.verify(run.toString()), "A keyed row cannot be relabelled as keyless");
    }

    private static void insertLegacy(
            Connection connection, UUID run, long sequence, String previous, String type, String stateJson)
            throws Exception {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String hash = Hashes.sha256(previous + "|" + sequence + "|" + at + "|" + type + "|legacy|state=" + stateJson);
        try (var insert = connection.prepareStatement(
                "INSERT INTO audit_events(run_id, seq, at, type, detail, previous_hash, event_hash, state_json, hash_scheme) VALUES (?, ?, ?, ?, 'legacy', ?, ?, ?, 1)")) {
            insert.setObject(1, run);
            insert.setLong(2, sequence);
            insert.setObject(3, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
            insert.setString(4, type);
            insert.setString(5, previous);
            insert.setString(6, hash);
            insert.setString(7, stateJson);
            insert.executeUpdate();
        }
    }
}
