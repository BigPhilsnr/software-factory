package dev.softwarefactory.persistence;

import dev.softwarefactory.workflow.RunState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class ControlRepositoryIntegrationTest {
    @Test void adoptsLegacySchemaWithoutRewritingRunBytes() throws Exception {
        String url = System.getenv().getOrDefault("CONTROL_DB_URL", "jdbc:postgresql://localhost:5434/control");
        String user = System.getenv().getOrDefault("CONTROL_DB_USER", "control");
        String password = System.getenv().getOrDefault("CONTROL_DB_PASSWORD", "control");
        String schema = "migration_test_" + java.util.UUID.randomUUID().toString().replace("-", "");
        try (var connection = java.sql.DriverManager.getConnection(url, user, password);
             var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".runs (id UUID PRIMARY KEY, state_json TEXT NOT NULL, updated_at TIMESTAMPTZ NOT NULL)");
                String original = "{  \"legacy\": true, \"timestamp\": 1.234 }";
                try (var insert = connection.prepareStatement("INSERT INTO " + schema + ".runs VALUES (?, ?, now())")) {
                    insert.setObject(1, java.util.UUID.randomUUID());
                    insert.setString(2, original);
                    insert.executeUpdate();
                }
                var repository = new ControlRepository(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
                repository.initialize();
                repository.initialize();
                try (var row = sql.executeQuery("SELECT state_json, revision FROM " + schema + ".runs")) {
                    assertTrue(row.next());
                    assertEquals(original, row.getString(1));
                    assertEquals(0, row.getLong(2));
                }
            } finally { sql.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    @Test void pooledLeaseReleasesSessionLockBeforeReturningConnection() throws Exception {
        var config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(System.getenv().getOrDefault("CONTROL_DB_URL", "jdbc:postgresql://localhost:5434/control"));
        config.setUsername(System.getenv().getOrDefault("CONTROL_DB_USER", "control"));
        config.setPassword(System.getenv().getOrDefault("CONTROL_DB_PASSWORD", "control"));
        config.setMaximumPoolSize(1);
        try (var pool = new com.zaxxer.hikari.HikariDataSource(config)) {
            var repository = new ControlRepository(pool);
            var run = java.util.UUID.randomUUID();
            long key = run.getMostSignificantBits() ^ run.getLeastSignificantBits();
            try (var lease = repository.lease(run.toString())) {
                assertFalse(lease.connection().isClosed());
            }
            try (var returned = pool.getConnection(); var query = returned.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                query.setLong(1, key);
                try (var row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertFalse(row.getBoolean(1), "The recycled session must not still own the lease");
                }
            }
        }
    }

    @Test void chatBudgetSurvivesRepositoryRecreationAndAuditsReservations() throws Exception {
        String url=System.getenv().getOrDefault("CONTROL_DB_URL","jdbc:postgresql://localhost:5434/control");
        String user=System.getenv().getOrDefault("CONTROL_DB_USER","control");
        String password=System.getenv().getOrDefault("CONTROL_DB_PASSWORD","control");
        String schema="chat_test_"+java.util.UUID.randomUUID().toString().replace("-", "");
        try(var connection=java.sql.DriverManager.getConnection(url,user,password); var sql=connection.createStatement()) {
            sql.execute("CREATE SCHEMA "+schema);
            try {
                String isolated=url+(url.contains("?")?"&":"?")+"currentSchema="+schema;
                var first=new ControlRepository(isolated,user,password);
                first.initialize(); first.reserveChatRequest(2);
                var restarted=new ControlRepository(isolated,user,password);
                restarted.reserveChatRequest(2);
                assertThrows(IllegalStateException.class,()->restarted.reserveChatRequest(2));
                try(var rows=sql.executeQuery("SELECT count(*) FROM "+schema+".chat_audit WHERE type='MODEL_CALL_RESERVED'")) {
                    assertTrue(rows.next()); assertEquals(2,rows.getInt(1));
                }
            } finally { sql.execute("DROP SCHEMA "+schema+" CASCADE"); }
        }
    }

    @Test void rejectsStaleWritersAndBindsStateToTheAuditChain() throws Exception {
        String url=System.getenv().getOrDefault("CONTROL_DB_URL","jdbc:postgresql://localhost:5434/control");
        String user=System.getenv().getOrDefault("CONTROL_DB_USER","control");
        String password=System.getenv().getOrDefault("CONTROL_DB_PASSWORD","control");
        var repository = new ControlRepository(url,user,password);
        repository.initialize();
        var state = new RunState(java.util.UUID.randomUUID().toString(), "audit-test", "hash");
        state.mode = "fixture";
        repository.record(state,"CREATED","test");
        try (var connection=java.sql.DriverManager.getConnection(url,user,password)) {
            try {
                var stale = repository.load(state.id);
                state.modelCalls = 1;
                repository.record(state,"UPDATED","test");
                assertThrows(IllegalStateException.class, () -> repository.record(stale,"STALE","must not overwrite"));
                assertEquals(1,repository.load(state.id).modelCalls);
                assertTrue(repository.auditValid(state.id));
                try (var query=connection.prepareStatement("UPDATE runs SET state_json = replace(state_json, '\"modelCalls\":1', '\"modelCalls\":99') WHERE id=?")) {
                    query.setObject(1,java.util.UUID.fromString(state.id)); query.executeUpdate();
                }
                assertFalse(repository.auditValid(state.id));
            } finally {
                try (var query=connection.prepareStatement("DELETE FROM audit_events WHERE run_id=?")) { query.setObject(1,java.util.UUID.fromString(state.id));query.executeUpdate(); }
                try (var query=connection.prepareStatement("DELETE FROM runs WHERE id=?")) { query.setObject(1,java.util.UUID.fromString(state.id));query.executeUpdate(); }
            }
        }
    }
}
