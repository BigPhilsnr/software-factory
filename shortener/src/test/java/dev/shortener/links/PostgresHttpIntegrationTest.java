package dev.shortener.links;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.shortener.analytics.AnalyticsConfiguration;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.specification.RequestSpecification;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real MVC, validation, security, Flyway, PostgreSQL and coalesced analytics over HTTP. */
@Tag("integration")
@Timeout(60)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "shortener.rate-limit.requests-per-window=" + PostgresHttpIntegrationTest.QUOTA,
    "shortener.analytics.flush-interval=100ms"})
@Import(PostgresHttpIntegrationTest.FixedTime.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PostgresHttpIntegrationTest {
    static final int QUOTA = 5;
    /** Fixed so rate-limit windows never roll over mid-test and analytics timestamps are exact. */
    static final Instant NOW = Instant.parse("2026-10-01T12:00:30Z");
    private static final Duration ANALYTICS_DEADLINE = Duration.ofSeconds(5);
    private static final String SCHEMA = "contract_" + UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE = env("SHORTENER_TEST_DB_URL", "SHORTENER_DB_URL", "jdbc:postgresql://localhost:5433/shortener");
    private static final String USER = env("SHORTENER_TEST_DB_USER", "SHORTENER_DB_USER", "shortener");
    private static final String PASSWORD = env("SHORTENER_TEST_DB_PASSWORD", "SHORTENER_DB_PASSWORD", "shortener");

    @LocalServerPort int port;
    @Autowired DataSource primary;
    @Autowired AnalyticsConfiguration.AnalyticsPool analyticsPool;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    private static String env(String name, String fallback, String defaultValue) {
        return System.getenv().getOrDefault(name, System.getenv().getOrDefault(fallback, defaultValue));
    }

    private static Connection database() throws SQLException { return DriverManager.getConnection(DATABASE, USER, PASSWORD); }

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws SQLException {
        try (var connection = database(); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        }
        properties.add("spring.datasource.url", () -> DATABASE + (DATABASE.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        properties.add("spring.datasource.username", () -> USER);
        properties.add("spring.datasource.password", () -> PASSWORD);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
    }

    @AfterAll static void removeOwnedSchema() throws SQLException {
        try (var connection = database(); var sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private RequestSpecification http() {
        // Per-request configuration avoids global REST Assured state and external redirects.
        return RestAssured.given().config(RestAssured.config().httpClient(HttpClientConfig.httpClientConfig()
                .setParam("http.connection.timeout", 5000).setParam("http.socket.timeout", 10000)))
            .baseUri("http://localhost").port(port)
            .redirects().follow(false).contentType("application/json");
    }

    private String create(String url) {
        return http().body(Map.of("url", url)).post("/api/shorten").then().statusCode(201).extract().path("code");
    }

    @Test void createsCanonicalAliasResolvedCaseInsensitively() {
        http().body(Map.of("url", "https://example.com/integration", "alias", "Mixed-Alias")).post("/api/shorten").then()
            .statusCode(201).body("code", equalTo("mixed-alias")).body("shortUrl", endsWith("/mixed-alias"));
        http().get("/MIXED-alias").then().statusCode(302).header("Location", "https://example.com/integration")
            .header("Cache-Control", containsString("no-store")).header("Referrer-Policy", "no-referrer");
    }

    @Test void countsGetRedirectsButNotHeadPreviews() {
        String code = create("https://example.com/counted");
        http().head("/" + code).then().statusCode(302).header("Location", "https://example.com/counted").body(emptyString());
        http().get("/api/urls/" + code + "/analytics").then().statusCode(200)
            .body("redirectCount", equalTo(0)).body("lastRedirectAt", nullValue());
        for (int i = 0; i < 3; i++) http().get("/" + code).then().statusCode(302);
        await().atMost(ANALYTICS_DEADLINE).untilAsserted(() -> http().get("/api/urls/" + code + "/analytics").then()
            .statusCode(200).body("code", equalTo(code)).body("redirectCount", equalTo(3))
            .body("lastRedirectAt", equalTo(NOW.toString())));
    }

    @Test void missingStatisticsRowReadsAsZeroNotFailure() throws SQLException {
        String code = create("https://example.com/orphan");
        try (var connection = database(); var sql = connection.prepareStatement(
                "DELETE FROM " + SCHEMA + ".link_stats WHERE link_id = (SELECT id FROM " + SCHEMA + ".links WHERE code = ?)")) {
            sql.setString(1, code);
            assertEquals(1, sql.executeUpdate());
        }
        http().get("/api/urls/" + code + "/analytics").then().statusCode(200).body("redirectCount", equalTo(0));
    }

    @Test void concurrentAliasClaimsHaveExactlyOneWinner() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Integer> claim = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return http().body(Map.of("url", "https://example.com/race", "alias", "race-alias")).post("/api/shorten").statusCode();
            };
            var first = workers.submit(claim);
            var second = workers.submit(claim);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(Set.of(201, 409), Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
        }
        try (var connection = database(); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT count(*) FROM " + SCHEMA + ".links WHERE code='race-alias'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "file:///etc/passwd", "http://localhost:8080/x", "http://169.254.169.254/latest",
        "http://[::1]/x", "https://user:password@example.com/x", "http://127.0.0.0x/x"})
    void rejectsInvalidAndPrivateTargets(String target) {
        http().body(Map.of("url", target)).post("/api/shorten").then().statusCode(400)
            .contentType(containsString("application/problem+json")).body("error", equalTo("invalid_request"));
    }

    @Test void rejectsMalformedBodiesAndAliases() {
        http().body("not-json").post("/api/shorten").then().statusCode(400).body("error", equalTo("invalid_request"));
        http().body(Map.of("url", "https://example.com", "alias", "x!")).post("/api/shorten").then().statusCode(400);
        http().body(Map.of("url", "https://example.com", "alias", "Logout")).post("/api/shorten").then().statusCode(400);
        http().contentType("text/plain").body("https://example.com").post("/api/shorten").then()
            .statusCode(415).body("error", equalTo("unsupported_media_type"));
    }

    @Test void rejectsOversizedBodies() {
        http().body(Map.of("url", "https://example.com/" + "x".repeat(66_000))).post("/api/shorten").then()
            .statusCode(413).body("error", equalTo("request_too_large"));
    }

    @Test void unknownCodesAndPathsAreNotFound() {
        http().get("/missing-code").then().statusCode(404).body("error", equalTo("not_found"));
        http().get("/api/urls/missing-code/analytics").then().statusCode(404).body("error", equalTo("not_found"));
        http().get("/no/such/path").then().statusCode(404).body("error", equalTo("not_found"));
        http().get("/logout").then().statusCode(404).body("error", equalTo("not_found"));
        http().post("/logout").then().statusCode(405).body("error", equalTo("method_not_allowed"));
    }

    @Test void limitsCreationWithoutReducingRedirectAvailability() {
        String code = create("https://example.com/limited");
        for (int count = 1; count < QUOTA; count++) create("https://example.com/" + count);
        http().body(Map.of("url", "https://example.com/overflow")).post("/api/shorten").then()
            .statusCode(429).header("Retry-After", "30").body("error", equalTo("rate_limited"));
        for (int count = 0; count < 35; count++) http().get("/" + code).then().statusCode(302);
    }

    @Test void exposesReadinessAndProtectsManagementInternals() {
        http().get("/actuator/health/readiness").then().statusCode(200).body("status", equalTo("UP"));
        http().get("/actuator/health").then().statusCode(200).body("components", nullValue());
        http().get("/actuator/env").then().statusCode(403);
        http().get("/actuator/metrics").then().statusCode(403);
    }

    @Test void propagatesRequestIds() {
        http().header("X-Request-Id", "it-123").get("/missing-code").then().header("X-Request-Id", "it-123");
        http().get("/missing-code").then().header("X-Request-Id", matchesPattern("[0-9a-f-]{36}"));
    }

    @Test void bothPoolsApplyStatementAndLockTimeouts() throws SQLException {
        for (DataSource pool : new DataSource[]{primary, analyticsPool.source()}) {
            try (var connection = pool.getConnection(); var sql = connection.createStatement()) {
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

    @Test void databaseEnforcesCanonicalCodesAndNonNegativeCounts() throws SQLException {
        try (var connection = database(); var sql = connection.createStatement()) {
            assertThrows(SQLException.class, () -> sql.execute(
                "INSERT INTO " + SCHEMA + ".links(code, target_url) VALUES ('UPPER-CASE', 'https://example.com')"));
            String code = create("https://example.com/constraint");
            assertThrows(SQLException.class, () -> sql.execute("UPDATE " + SCHEMA + ".link_stats SET redirect_count = -1 "
                + "WHERE link_id = (SELECT id FROM " + SCHEMA + ".links WHERE code = '" + code + "')"));
        }
    }
}
