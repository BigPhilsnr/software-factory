package dev.softwarefactory.operator.web;

import dev.softwarefactory.execution.CommandRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Test-owned schema and Git clone; never writes operator runs or the source checkout. */
final class FactoryHttpTestEnvironment implements AutoCloseable {
    private final String database = System.getenv().getOrDefault("CONTROL_DB_URL", "jdbc:postgresql://localhost:5434/control");
    private final String user = System.getenv().getOrDefault("CONTROL_DB_USER", "control");
    private final String password = System.getenv().getOrDefault("CONTROL_DB_PASSWORD", "control");
    private final String schema = "http_test_" + UUID.randomUUID().toString().replace("-", "");
    private Path workspace;

    void configure(DynamicPropertyRegistry properties) throws Exception {
        Path source = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (source.getFileName().toString().equals("factory")) source = source.getParent();
        // Under the user's checkout so Docker Desktop/Colima can mount candidate files.
        Files.createDirectories(source.resolve(".runs"));
        workspace = Files.createTempDirectory(source.resolve(".runs"), "http-integration-");
        CommandRunner.checked(source, List.of("git", "clone", "--quiet", "--shared", source.toString(), workspace.toString()), Duration.ofSeconds(30));
        // Exercise current scenario fixtures, including any locally edited scenario definitions.
        try (var files = Files.walk(source.resolve("scenarios"))) {
            for (Path file : files.toList()) {
                Path target = workspace.resolve(source.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(target);
                else Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        try (var connection = DriverManager.getConnection(database, user, password); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
        }
        properties.add("spring.datasource.url", () -> database + (database.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.flyway.schemas", () -> schema);
        properties.add("factory.workspace", () -> workspace.toString());
    }

    Path artifact(String run, String name) { return workspace.resolve("evidence").resolve(run).resolve(name); }

    @Override public void close() throws Exception {
        try (var connection = DriverManager.getConnection(database, user, password); var sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            if (workspace != null) {
                try (var files = Files.walk(workspace)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
                }
            }
        }
    }
}
