package dev.shortener.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP + Flyway + PostgreSQL, isolated in a disposable schema on the local test database. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class PostgresHttpIntegrationTest {
    private static final String SCHEMA = "contract_" + UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE = "jdbc:postgresql://localhost:5433/shortener";
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws Exception {
        try (var connection = DriverManager.getConnection(DATABASE, "shortener", "shortener"); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        properties.add("spring.datasource.url", () -> DATABASE + "?currentSchema=" + SCHEMA);
        properties.add("spring.datasource.username", () -> "shortener");
        properties.add("spring.datasource.password", () -> "shortener");
        properties.add("spring.flyway.schemas", () -> SCHEMA);
    }

    @AfterAll static void removeOwnedSchema() throws Exception {
        try (var connection = DriverManager.getConnection(DATABASE, "shortener", "shortener"); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private HttpRequest request(String path, String body) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(5));
        return body == null ? request.GET().build() : request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private HttpResponse<String> call(String path, String body) throws Exception {
        return client.send(request(path, body), HttpResponse.BodyHandlers.ofString());
    }

    @Test void verifiesContractUniquenessAnalyticsAndRedirectAvailability() throws Exception {
        String target = "https://example.com/integration";
        String body = "{\"url\":\"" + target + "\",\"alias\":\"Race-Alias\"}";
        var first = client.sendAsync(request("/api/shorten", body), HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(request("/api/shorten", body), HttpResponse.BodyHandlers.ofString());
        assertEquals(Set.of(201,409), Set.of(first.join().statusCode(), second.join().statusCode()));
        var redirect = call("/race-alias", null);
        assertEquals(302, redirect.statusCode());
        assertEquals(target, redirect.headers().firstValue("Location").orElseThrow());
        assertEquals(400, call("/api/shorten", "{\"url\":\"file:///etc/passwd\"}").statusCode());
        assertEquals(400, call("/api/shorten", "not-json").statusCode());
        assertEquals(404, call("/missing-code", null).statusCode());
        var analytics = call("/api/urls/race-alias/analytics", null);
        assertEquals(200, analytics.statusCode());
        assertTrue(analytics.body().matches("(?s).*\\\"redirectCount\\\":\\s*[1-9][0-9]*.*"), analytics.body());
        boolean limited = false;
        for (int i=0; i<65; i++) {
            var response = call("/api/shorten", "{\"url\":\"" + target + "\"}");
            if (response.statusCode() == 429) {
                assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) > 0);
                limited = true; break;
            }
            assertEquals(201, response.statusCode());
        }
        assertTrue(limited, "Creation must be bounded");
        for (int i=0; i<35; i++) assertEquals(302, call("/race-alias", null).statusCode());
        try (var connection = DriverManager.getConnection(DATABASE, "shortener", "shortener"); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + SCHEMA + ".links WHERE code='race-alias'")) {
            assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
        }
    }
}
