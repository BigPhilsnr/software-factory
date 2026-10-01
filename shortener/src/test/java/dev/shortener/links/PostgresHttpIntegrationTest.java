package dev.shortener.links;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MVC, validation, security, Flyway, PostgreSQL and asynchronous analytics. */
@Tag("integration")
@Timeout(60)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "debug=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PostgresHttpIntegrationTest {
    private static final String SCHEMA = "contract_" + UUID.randomUUID().toString().replace("-", "");
    private static final String DATABASE = System.getenv().getOrDefault("SHORTENER_TEST_DB_URL", System.getenv().getOrDefault("SHORTENER_DB_URL", "jdbc:postgresql://localhost:5433/shortener"));
    private static final String USER = System.getenv().getOrDefault("SHORTENER_TEST_DB_USER", System.getenv().getOrDefault("SHORTENER_DB_USER", "shortener"));
    private static final String PASSWORD = System.getenv().getOrDefault("SHORTENER_TEST_DB_PASSWORD", System.getenv().getOrDefault("SHORTENER_DB_PASSWORD", "shortener"));
    @LocalServerPort int port;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) throws Exception {
        try (var connection = DriverManager.getConnection(DATABASE, USER, PASSWORD); var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        }
        properties.add("spring.datasource.url", () -> DATABASE + (DATABASE.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        properties.add("spring.datasource.username", () -> USER);
        properties.add("spring.datasource.password", () -> PASSWORD);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
    }

    @AfterAll static void removeOwnedSchema() throws Exception {
        try (var connection = DriverManager.getConnection(DATABASE, USER, PASSWORD); var sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private RequestSpecification http() {
        // Per-request configuration avoids global REST Assured state and external redirects.
        return RestAssured.given().config(RestAssured.config().httpClient(
                io.restassured.config.HttpClientConfig.httpClientConfig()
                    .setParam("http.connection.timeout", 5000).setParam("http.socket.timeout", 10000)))
            .baseUri("http://localhost").port(port)
            .redirects().follow(false).contentType("application/json");
    }

    @Test void createsCanonicalAliasAndCountsGetButNotHead() {
        String target = "https://example.com/integration";
        http().body(Map.of("url", target, "alias", "Mixed-Alias")).post("/api/shorten").then()
            .statusCode(201).body("code", equalTo("mixed-alias"))
            .body("shortUrl", endsWith("/mixed-alias"));
        http().head("/Mixed-Alias").then().statusCode(302).header("Location", target).body(isEmptyString());
        http().get("/api/urls/MIXED-ALIAS/analytics").then().statusCode(200)
            .body("redirectCount", equalTo(0)).body("lastRedirectAt", nullValue());
        http().get("/Mixed-Alias").then().statusCode(302).header("Location", target);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            String timestamp = http().get("/api/urls/mixed-alias/analytics").then().statusCode(200)
                .body("code", equalTo("mixed-alias")).body("redirectCount", equalTo(1))
                .extract().path("lastRedirectAt");
            assertDoesNotThrow(() -> Instant.parse(timestamp));
        });
    }

    @Test void concurrentAliasClaimsHaveExactlyOneWinner() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> claim = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return http().body(Map.of("url", "https://example.com/race", "alias", "race-alias"))
                    .post("/api/shorten").statusCode();
            };
            var first = workers.submit(claim);
            var second = workers.submit(claim);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(Set.of(201, 409), Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
        }
        try (var connection = DriverManager.getConnection(DATABASE, USER, PASSWORD); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT count(*) FROM " + SCHEMA + ".links WHERE code='race-alias'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test void rejectsInvalidRequestsAndPrivateTargets() {
        for (String target : new String[]{"", "file:///etc/passwd", "http://localhost:8080/x",
                "http://169.254.169.254/latest", "http://[::1]/x", "https://user:password@example.com/x"}) {
            http().body(Map.of("url", target)).post("/api/shorten").then().statusCode(400)
                .body("error", equalTo("invalid_request"));
        }
        http().body("not-json").post("/api/shorten").then().statusCode(400);
        http().body(Map.of("url", "https://example.com", "alias", "x!")).post("/api/shorten").then().statusCode(400);
        http().body(Map.of("url", "https://example.com/" + "x".repeat(66000))).post("/api/shorten").then().statusCode(413);
        http().get("/missing-code").then().statusCode(404);
        http().get("/api/urls/missing-code/analytics").then().statusCode(404);
    }

    @Test void limitsCreationWithoutReducingRedirectAvailability() {
        String code = http().body(Map.of("url", "https://example.com/limited")).post("/api/shorten")
            .then().statusCode(201).extract().path("code");
        for (int count = 1; count < 30; count++) {
            http().body(Map.of("url", "https://example.com/" + count)).post("/api/shorten").then().statusCode(201);
        }
        http().body(Map.of("url", "https://example.com/overflow")).post("/api/shorten").then()
            .statusCode(429).header("Retry-After", matchesPattern("[1-9][0-9]*"));
        for (int count = 0; count < 35; count++) http().get("/" + code).then().statusCode(302);
    }

    @Test void exposesReadinessAndProtectsManagementInternals() {
        http().get("/actuator/health/readiness").then().statusCode(200).body("status", equalTo("UP"));
        http().get("/actuator/env").then().statusCode(403);
    }
}
