package dev.softwarefactory.testing;

import dev.softwarefactory.execution.CommandRunner;
import dev.softwarefactory.persistence.AuditKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Test-owned control schema and disposable Git clone. Never writes operator runs, evidence or the source
 * checkout; candidates and evidence live inside the clone and are removed with it.
 */
public final class IsolatedFactoryEnvironment implements AutoCloseable {
    public static final AuditKey AUDIT_KEY = AuditKey.of("integration-test-audit-key-0123456789abcdef");
    private static final Duration CLONE_TIMEOUT = Duration.ofSeconds(60);
    private final String database =
            System.getenv().getOrDefault("CONTROL_DB_URL", "jdbc:postgresql://localhost:5434/control");
    private final String user = System.getenv().getOrDefault("CONTROL_DB_USER", "control");
    private final String password = System.getenv().getOrDefault("CONTROL_DB_PASSWORD", "control");
    private final String schema;
    private Path workspace;

    public IsolatedFactoryEnvironment(String prefix) {
        this.schema = prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** Creates the schema only; for repository tests that need no Git checkout. */
    public IsolatedFactoryEnvironment withSchema() throws SQLException {
        try (var connection = DriverManager.getConnection(database, user, password);
                var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
        }
        return this;
    }

    /** Also clones the current checkout (with current scenario files) under {@code .runs/} so Docker can mount it. */
    public IsolatedFactoryEnvironment withWorkspace() throws IOException, InterruptedException {
        Path source = sourceRoot();
        Files.createDirectories(source.resolve(".runs"));
        workspace = Files.createTempDirectory(source.resolve(".runs"), schema.replace('_', '-') + "-");
        CommandRunner.checked(
                source,
                List.of("git", "clone", "--quiet", "--shared", source.toString(), workspace.toString()),
                CLONE_TIMEOUT);
        try (var files = Files.walk(source.resolve("scenarios"))) {
            for (Path file : files.toList()) {
                Path target = workspace.resolve(source.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(target);
                else Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return this;
    }

    public static Path sourceRoot() {
        Path source = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        return source.getFileName().toString().equals("factory") ? source.getParent() : source;
    }

    public String url() {
        return database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    public String schema() {
        return schema;
    }

    public Path workspace() {
        return workspace;
    }

    @Override
    public void close() throws SQLException, IOException {
        try (var connection = DriverManager.getConnection(database, user, password);
                var sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            if (workspace != null && Files.exists(workspace)) {
                try (var files = Files.walk(workspace)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
                }
            }
        }
    }
}
