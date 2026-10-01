package dev.softwarefactory.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.testing.IsolatedFactoryEnvironment;
import dev.softwarefactory.workflow.RunState;
import dev.softwarefactory.workflow.WorkflowConflictException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class ControlRepositoryIntegrationTest {
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

    private ControlRepository repository(AuditKey key) {
        var repository = new ControlRepository(environment.url(), environment.user(), environment.password(), key);
        repository.initialize();
        return repository;
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
            repository(IsolatedFactoryEnvironment.AUDIT_KEY);
            repository(IsolatedFactoryEnvironment.AUDIT_KEY);
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
            var repository = new ControlRepository(pool, IsolatedFactoryEnvironment.AUDIT_KEY);
            String run = UUID.randomUUID().toString();
            try (var lease = repository.lease(run)) {
                assertFalse(lease.connection().isClosed());
                // The competing lease runs on another thread and pooled session.
                var competitor = CompletableFuture.supplyAsync(() -> {
                    try (var second = repository.lease(run)) {
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
            try (var again = repository.lease(run)) {
                assertFalse(again.connection().isClosed(), "Released leases can be re-acquired");
            }
            try (var returned = pool.getConnection();
                    var query = returned.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
                query.setInt(1, ControlRepository.LEASE_NAMESPACE);
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
        repository(IsolatedFactoryEnvironment.AUDIT_KEY).reserveChatRequest(2);
        var restarted = new ControlRepository(
                environment.url(), environment.user(), environment.password(), IsolatedFactoryEnvironment.AUDIT_KEY);
        restarted.reserveChatRequest(2);
        assertThrows(WorkflowConflictException.class, () -> restarted.reserveChatRequest(2));
        try (var connection = connection();
                var sql = connection.createStatement();
                var rows = sql.executeQuery("SELECT count(*) FROM chat_audit WHERE type='MODEL_CALL_RESERVED'")) {
            assertTrue(rows.next());
            assertEquals(2, rows.getInt(1), "A refused reservation leaves no audit row");
        }
    }

    @Test
    void rejectsStaleWritersAndBindsStateToTheKeyedAuditChain() throws Exception {
        var repository = repository(IsolatedFactoryEnvironment.AUDIT_KEY);
        var state = new RunState(UUID.randomUUID().toString(), "audit-test", "hash");
        state.mode = "fixture";
        repository.record(state, "CREATED", "test");
        var stale = repository.load(state.id);
        state.modelCalls = 1;
        repository.record(state, "UPDATED", "test");
        assertThrows(WorkflowConflictException.class, () -> repository.record(stale, "STALE", "must not overwrite"));
        assertEquals(1, repository.load(state.id).modelCalls);
        assertTrue(repository.auditValid(state.id));
        assertFalse(
                repository(AuditKey.of("a-different-audit-key-0123456789abcdef"))
                        .auditValid(state.id),
                "Hashes cannot be verified, or recomputed, without the key");
        try (var connection = connection();
                var query = connection.prepareStatement(
                        "UPDATE runs SET state_json = replace(state_json, '\"modelCalls\":1', '\"modelCalls\":99') WHERE id=?")) {
            query.setObject(1, UUID.fromString(state.id));
            query.executeUpdate();
        }
        assertFalse(repository.auditValid(state.id));
    }

    @Test
    void legacyRowsStayVerifiableAndCannotBeUsedToDowngradeTheChain() throws Exception {
        var repository = repository(IsolatedFactoryEnvironment.AUDIT_KEY);
        UUID run = UUID.randomUUID();
        var state = new RunState(run.toString(), "legacy", "hash");
        state.mode = "fixture";
        String stateJson = dev.softwarefactory.serialization.Json.MAPPER.writeValueAsString(state);
        try (var connection = connection()) {
            try (var insert =
                    connection.prepareStatement("INSERT INTO runs(id, state_json, updated_at) VALUES (?, ?, now())")) {
                insert.setObject(1, run);
                insert.setString(2, stateJson);
                insert.executeUpdate();
            }
            insertLegacy(connection, run, 1, GENESIS, "RUN_CREATED", stateJson);
        }
        assertTrue(repository.auditValid(run.toString()), "Rows written before the HMAC scheme still verify");
        RunState loaded = repository.load(run.toString());
        repository.record(loaded, "UPGRADED", "first keyed row");
        assertTrue(repository.auditValid(run.toString()));
        try (var connection = connection();
                var downgrade = connection.prepareStatement(
                        "UPDATE audit_events SET hash_scheme = 1 WHERE run_id = ? AND seq = 2")) {
            downgrade.setObject(1, run);
            downgrade.executeUpdate();
        }
        assertFalse(repository.auditValid(run.toString()), "A keyed row cannot be relabelled as keyless");
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
