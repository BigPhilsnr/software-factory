package dev.softwarefactory.operator.web;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.softwarefactory.operator.api.FactoryService;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real HTTP -> security -> orchestration -> Git/Docker -> PostgreSQL; no model calls. */
@Tag("integration")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = FactoryWebServer.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "debug=false")
@DirtiesContext
class FactoryHttpIntegrationTest {
    private static final FactoryHttpTestEnvironment ENVIRONMENT = new FactoryHttpTestEnvironment();

    @LocalServerPort
    int port;

    @Autowired
    FactoryService service;

    private String token;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) throws Exception {
        ENVIRONMENT.configure(properties);
    }

    @BeforeEach
    void operatorToken() {
        token = http().get("/factory/api/config")
                .then()
                .statusCode(200)
                .body("liveReady", equalTo(false))
                .extract()
                .path("token");
    }

    @AfterAll
    void cleanup() throws Exception {
        try {
            if (service != null) service.close(); // Drain workers before removing their resources.
        } finally {
            ENVIRONMENT.close();
        }
    }

    private RequestSpecification http() {
        return RestAssured.given()
                .config(RestAssured.config()
                        .httpClient(io.restassured.config.HttpClientConfig.httpClientConfig()
                                .setParam("http.connection.timeout", 5000)
                                .setParam("http.socket.timeout", 10000)))
                .baseUri("http://localhost")
                .port(port)
                .redirects()
                .follow(false)
                .contentType("application/json");
    }

    private RequestSpecification operator() {
        return http().header("X-Factory-Token", token);
    }

    private String start(String scenario) {
        return operator()
                .body(Map.of("kind", "scenario", "scenario", scenario, "mode", "fixture"))
                .post("/factory/api/runs")
                .then()
                .statusCode(200)
                .body("mode", equalTo("fixture"))
                .body("status", equalTo("CREATED"))
                .body("modelCalls", equalTo(0))
                .extract()
                .path("id");
    }

    private void action(String run, Map<String, String> body, int status) {
        operator().body(body).post("/factory/api/runs/{id}/actions", run).then().statusCode(status);
    }

    private JsonPath detail(String run) {
        return http().get("/factory/api/runs/{id}", run)
                .then()
                .statusCode(200)
                .body("state.modelCalls", equalTo(0))
                .body("auditValid", equalTo(true))
                .extract()
                .jsonPath();
    }

    private JsonPath idle(String run) {
        await().atMost(Duration.ofSeconds(180))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertFalse(detail(run).getBoolean("busy")));
        return detail(run);
    }

    @Test
    void enforcesOperatorBoundaryAndValidatesRequests() {
        var create = Map.of("kind", "scenario", "scenario", "greenfield", "mode", "fixture");
        http().body(create).post("/factory/api/runs").then().statusCode(403);
        operator()
                .header("Origin", "https://untrusted.example")
                .body(create)
                .post("/factory/api/runs")
                .then()
                .statusCode(403);
        operator()
                .body(Map.of("kind", "unknown"))
                .post("/factory/api/runs")
                .then()
                .statusCode(400);
        operator()
                .body(Map.of("kind", "scenario", "scenario", "greenfield", "mode", "live"))
                .post("/factory/api/runs")
                .then()
                .statusCode(409); // Surefire strips the provider key.
        http().get("/factory/api/runs/not-a-uuid").then().statusCode(400);
        http().get("/factory/api/runs/00000000-0000-0000-0000-000000000000")
                .then()
                .statusCode(404);
        operator()
                .body(Map.of("kind", "feature", "requirement", "x".repeat(70000)))
                .post("/factory/api/runs")
                .then()
                .statusCode(413);
        http().get("/list-apps").then().statusCode(200).body("$", hasItem("software_factory"));
        http().get("/actuator/health/readiness").then().statusCode(200).body("status", equalTo("UP"));
    }

    @Test
    void returnsTypedJsonErrorsAndSecurityHeaders() {
        http().body(Map.of("kind", "scenario"))
                .post("/factory/api/runs")
                .then()
                .statusCode(403)
                .contentType("application/json")
                .body("error", containsString("Reload the operator page"));
        operator()
                .contentType("text/plain")
                .body("kind=scenario")
                .post("/factory/api/runs")
                .then()
                .statusCode(415);
        operator()
                .body("{not json")
                .post("/factory/api/runs")
                .then()
                .statusCode(400)
                .body("error", equalTo("Invalid request fields"));
        operator()
                .body(Map.of("action", "approve", "hash", "0".repeat(64)))
                .post("/factory/api/runs/{id}/actions", "00000000-0000-0000-0000-000000000000")
                .then()
                .statusCode(404);
        http().get("/factory/api/runs/00000000-0000-0000-0000-000000000000/artifacts/plan-v1.txt")
                .then()
                .statusCode(404)
                .body("error", containsString("Artifact not found"));
        http().get("/factory/")
                .then()
                .statusCode(200)
                .header("Content-Security-Policy", containsString("frame-ancestors 'none'"))
                .header("Content-Security-Policy", containsString("script-src 'self'"))
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY");
        http().get("/factory/api/config")
                .then()
                .statusCode(200)
                .body("validator.ready", notNullValue())
                .body("liveBlocker", not(emptyString()));
    }

    @Test
    void chatCommandsThatChangeWorkflowStateRequireTheOperatorToken() {
        String session = http().body(Map.of())
                .post("/apps/software_factory/users/tester/sessions")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
        Map<String, Object> approve = Map.of(
                "appName",
                "software_factory",
                "userId",
                "tester",
                "sessionId",
                session,
                "newMessage",
                Map.of("role", "user", "parts", java.util.List.of(Map.of("text", "/approve " + "0".repeat(64)))));
        http().body(approve).post("/run").then().statusCode(403).body("error", containsString("operator token"));
        operator().body(approve).post("/run").then().statusCode(200).body(containsString("Action not performed"));
        Map<String, Object> help = Map.of(
                "appName",
                "software_factory",
                "userId",
                "tester",
                "sessionId",
                session,
                "newMessage",
                Map.of("role", "user", "parts", java.util.List.of(Map.of("text", "/help"))));
        http().body(help).post("/run").then().statusCode(200).body(containsString("Software factory"));
    }

    @Test
    void requiresClarificationAndExactHashBeforeRejectingProposal() {
        String run = start("ambiguous");
        action(run, Map.of("action", "advance"), 200);
        var questions = idle(run);
        assertEquals("clarify", questions.getString("state.pendingClarificationTask"));
        assertFalse(questions.getList("clarificationContext").isEmpty());
        action(run, Map.of("action", "advance"), 409);
        action(
                run,
                Map.of(
                        "action",
                        "clarify",
                        "answer",
                        "Single instance, 100 requests/sec, 50ms p95; bounded best-effort analytics."),
                200);
        var proposal = idle(run);
        assertEquals("apply", proposal.getString("review.task"));
        String hash = proposal.getString("review.hash");
        action(run, Map.of("action", "approve", "hash", "0".repeat(64)), 409);
        assertEquals(hash, detail(run).getString("state.pendingApprovalHash"));
        action(
                run,
                Map.of("action", "revise", "task", "apply", "feedback", "Regenerate for a second human review."),
                200);
        var revised = idle(run);
        assertEquals("Regenerate for a second human review.", revised.getString("state.reviewFeedback.apply"));
        assertTrue(revised.getList("artifacts", String.class).contains("apply-v2.txt"));
        action(run, Map.of("action", "reject", "hash", revised.getString("review.hash")), 200);
        assertEquals("NOT_APPROVED", idle(run).getString("state.status"));
        action(run, Map.of("action", "advance"), 409);
    }

    @Test
    void validatesCandidateThenRequiresSeparateReleaseApproval() {
        String run = start("greenfield");
        action(run, Map.of("action", "advance"), 200);
        var proposal = idle(run);
        assertEquals("apply", proposal.getString("review.task"));
        assertNotEquals("DONE", proposal.getString("state.tasks.apply"));
        action(run, Map.of("action", "approve", "hash", proposal.getString("review.hash")), 200);
        var release = idle(run);
        assertEquals("release", release.getString("review.task"));
        assertEquals("DONE", release.getString("state.tasks.validate"));
        assertFalse(release.getList("validationEvidence").isEmpty());
        assertNotEquals("COMPLETED", release.getString("state.status"));
        action(run, Map.of("action", "approve", "hash", release.getString("review.hash")), 200);
        var completed = idle(run);
        assertEquals("COMPLETED", completed.getString("state.status"));
        assertNotNull(completed.getString("state.finishedAt"));
        assertTrue(completed.getList("events.type", String.class).contains("APPROVAL_GRANTED"));
    }

    @Test
    void refusesTamperedEvidenceEvenWithPreviouslyValidApprovalHash() throws Exception {
        String run = start("greenfield");
        action(run, Map.of("action", "advance"), 200);
        String hash = idle(run).getString("review.hash");
        Files.writeString(ENVIRONMENT.artifact(run, "understand-v1.txt"), "tampered fixture evidence");
        action(run, Map.of("action", "approve", "hash", hash), 409);
        var stopped = idle(run);
        assertEquals("SAFE_STOPPED", stopped.getString("state.status"));
        assertFalse(stopped.getList("events.type", String.class).contains("APPROVAL_GRANTED"));
    }
}
