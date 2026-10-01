package dev.shortener.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.shortener.PostgresSchema;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The analytics bulkhead must inherit the primary pool's driver-level safety timeouts. */
@Tag("integration")
@Timeout(60)
@SpringBootTest
class AnalyticsPoolIntegrationTest {
    private static final PostgresSchema SCHEMA = new PostgresSchema();

    @Autowired
    DataSource primary;

    @Autowired
    AnalyticsConfiguration.AnalyticsPool analyticsPool;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws SQLException {
        SCHEMA.createAndRegister(properties);
    }

    @AfterAll
    static void removeOwnedSchema() throws SQLException {
        SCHEMA.drop();
    }

    @Test
    void bothPoolsApplyStatementAndLockTimeouts() throws SQLException {
        for (DataSource pool : new DataSource[] {primary, analyticsPool.source()}) {
            try (var connection = pool.getConnection();
                    var sql = connection.createStatement()) {
                assertEquals("2s", setting(sql, "statement_timeout"));
                assertEquals("1s", setting(sql, "lock_timeout"));
            }
        }
    }

    private static String setting(Statement sql, String name) throws SQLException {
        try (var rows = sql.executeQuery("SHOW " + name)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
