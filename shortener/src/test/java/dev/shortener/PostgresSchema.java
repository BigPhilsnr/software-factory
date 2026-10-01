package dev.shortener;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * A uniquely named PostgreSQL schema owned by one integration test class, so suites never see each other's
 * rows. Connection settings come from {@code SHORTENER_TEST_DB_*}, then {@code SHORTENER_DB_*}, then the
 * local Compose defaults.
 */
public final class PostgresSchema {
    private static final String DATABASE =
            env("SHORTENER_TEST_DB_URL", "SHORTENER_DB_URL", "jdbc:postgresql://localhost:5433/shortener");
    private static final String USER = env("SHORTENER_TEST_DB_USER", "SHORTENER_DB_USER", "shortener");
    private static final String PASSWORD = env("SHORTENER_TEST_DB_PASSWORD", "SHORTENER_DB_PASSWORD", "shortener");

    private final String name = "contract_" + UUID.randomUUID().toString().replace("-", "");

    private static String env(String name, String fallback, String defaultValue) {
        return System.getenv().getOrDefault(name, System.getenv().getOrDefault(fallback, defaultValue));
    }

    public String name() {
        return name;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE, USER, PASSWORD);
    }

    /** Creates the schema and points the application's datasource and Flyway at it. */
    public void createAndRegister(DynamicPropertyRegistry properties) throws SQLException {
        try (var connection = connect();
                var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA IF NOT EXISTS " + name);
        }
        properties.add(
                "spring.datasource.url",
                () -> DATABASE + (DATABASE.contains("?") ? "&" : "?") + "currentSchema=" + name);
        properties.add("spring.datasource.username", () -> USER);
        properties.add("spring.datasource.password", () -> PASSWORD);
        properties.add("spring.flyway.schemas", () -> name);
    }

    public void drop() throws SQLException {
        try (var connection = connect();
                var sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS " + name + " CASCADE");
        }
    }
}
