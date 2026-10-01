package dev.softwarefactory.operator.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.softwarefactory.audit.AuditEvent;
import dev.softwarefactory.audit.AuditTrail;
import dev.softwarefactory.audit.ChatLedger;
import dev.softwarefactory.audit.RunMetrics;
import dev.softwarefactory.platform.Json;
import dev.softwarefactory.run.DurableRunStore;
import dev.softwarefactory.run.RunState;
import dev.softwarefactory.run.RunStatus;
import dev.softwarefactory.run.TaskStatus;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.Stage;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The operator's read side over real evidence files, with the control database mocked. */
class RunViewsTest {
    private static final String ID = "00000000-0000-0000-0000-0000000000aa";
    private static final Instant START = Instant.parse("2026-02-01T08:00:00Z");

    @TempDir
    Path root;

    private final DurableRunStore runs = mock(DurableRunStore.class);
    private final AuditTrail trail = mock(AuditTrail.class);
    private RunViews views;
    private RunState state;

    @BeforeEach
    void setup() throws Exception {
        views = new RunViews(root, new ControlRecords(runs, trail, mock(ChatLedger.class)));
        var spec = new ScenarioSpec(
                "demo",
                "Add expiry",
                "url-v4",
                List.of(
                        task("understand", TaskKind.ARTIFACT, List.of()),
                        task("clarify", TaskKind.CLARIFY, List.of("understand")),
                        task("apply", TaskKind.PATCH, List.of("clarify")),
                        task("validate", TaskKind.VALIDATE, List.of("apply"))));
        Path scenario = root.resolve("scenario.json");
        Files.writeString(scenario, Json.MAPPER.writeValueAsString(spec));
        state = new RunState(ID, "demo", "hash");
        state.specPath = scenario.toString();
        state.mode = "fixture";
        state.startedAt = START;
        state.baselineCommit = "abc123";
        when(runs.auditValid(ID)).thenReturn(true);
        when(trail.timeline(ID)).thenReturn(List.of());
        when(trail.recentEvents(ID)).thenReturn(List.of());
    }

    private static TaskSpec task(String id, TaskKind kind, List<String> dependencies) {
        return new TaskSpec(
                id,
                Stage.IMPLEMENTATION,
                dependencies,
                kind,
                "role",
                "prompt",
                null,
                List.of(),
                kind == TaskKind.PATCH ? List.of("shortener") : List.of(),
                false);
    }

    private void evidence(String name, String content) throws IOException {
        Path folder = Files.createDirectories(root.resolve("evidence").resolve(ID));
        Files.writeString(folder.resolve(name), content);
    }

    private static Map<String, Object> event(long sequence, String type, String detail) {
        return Map.of("sequence", sequence, "at", "2026-02-01 08:00:00+00", "type", type, "detail", detail);
    }

    @Test
    void aFreshRunShowsItsPlanAndAVerifiedEmptyRecord() throws Exception {
        Map<String, Object> detail = views.detail(state, true, "");
        assertEquals(
                List.of(
                        "state",
                        "requirement",
                        "tasks",
                        "busy",
                        "error",
                        "events",
                        "metrics",
                        "auditValid",
                        "auditCheckedAt",
                        "artifacts",
                        "clarificationContext",
                        "validationEvidence"),
                List.copyOf(detail.keySet()),
                "The response shape is part of the HTTP contract");
        assertEquals("Add expiry", detail.get("requirement"));
        assertEquals(4, ((List<?>) detail.get("tasks")).size());
        assertEquals(true, detail.get("busy"));
        assertEquals(true, detail.get("auditValid"));
        assertEquals(List.of(), detail.get("artifacts"));
        assertFalse(((RunMetrics) detail.get("metrics")).terminal());
    }

    @Test
    void aPendingClarificationComesWithTheUpstreamOutputsNeededToAnswerIt() throws Exception {
        evidence("understand-v1.txt", "Which regions must be supported?");
        state.status = RunStatus.PAUSED;
        state.pendingClarificationTask = "clarify";
        state.artifactVersions.put("understand", 1);
        Map<String, Object> detail = views.detail(state, false, "");
        assertEquals(
                List.of(Map.of("task", "understand", "text", "Which regions must be supported?")),
                detail.get("clarificationContext"));
        assertEquals(List.of("understand-v1.txt"), detail.get("artifacts"));
        assertFalse(detail.containsKey("pauseKind"), "A clarification is not a failure pause");
    }

    @Test
    void aPendingReviewShowsExactlyTheProposalAndHashToApprove() throws Exception {
        evidence("apply-v2.txt", "diff --git a/x b/x");
        state.status = RunStatus.PAUSED;
        state.pendingApprovalTask = "apply";
        state.pendingApprovalHash = "f".repeat(64);
        state.artifactVersions.put("apply", 2);
        Map<String, Object> detail = views.detail(state, false, "");
        assertEquals(
                Map.of(
                        "task", "apply",
                        "hash", "f".repeat(64),
                        "artifact", "apply-v2.txt",
                        "patch", "diff --git a/x b/x",
                        "baseline", "abc123"),
                detail.get("review"));

        Files.delete(root.resolve("evidence").resolve(ID).resolve("apply-v2.txt"));
        var review = (Map<?, ?>) views.detail(state, false, "").get("review");
        assertTrue(review.get("patch").toString().startsWith("Evidence unavailable: apply-v2.txt"));
    }

    @Test
    void aPausedRunExplainsWhyWithTheNewestPauseEventAndItsDiagnostic() throws Exception {
        evidence("validate-error-v1.txt", "ValidationFailedException: tests failed");
        evidence("validate-v1.txt", "Sandboxed Maven tests passed");
        state.status = RunStatus.PAUSED;
        state.tasks.put("validate", TaskStatus.DONE);
        state.artifactVersions.put("validate", 1);
        when(trail.recentEvents(ID))
                .thenReturn(List.of(
                        event(9, "RUN_RESUMED", "demo"),
                        event(8, "REVISION_REQUIRED", "validate:validate-error-v1"),
                        event(7, "RETRY_AVAILABLE", "apply:apply-error-v1")));
        Map<String, Object> detail = views.detail(state, false, "Advance failed: IOException.");
        assertEquals("revision", detail.get("pauseKind"));
        assertEquals("validate:validate-error-v1\nValidationFailedException: tests failed", detail.get("retryReason"));
        assertEquals("Advance failed: IOException.", detail.get("error"));
        assertEquals(
                List.of(Map.of("task", "validate", "text", "Sandboxed Maven tests passed")),
                detail.get("validationEvidence"));

        when(trail.recentEvents(ID))
                .thenReturn(List.of(event(10, "INFRASTRUCTURE_UNAVAILABLE", "validate:Docker down")));
        detail = views.detail(state, false, "");
        assertEquals("infrastructure", detail.get("pauseKind"));
        assertEquals("validate:Docker down", detail.get("retryReason"));

        when(trail.recentEvents(ID)).thenReturn(List.of(event(11, "RETRY_AVAILABLE", "apply:apply-error-v9")));
        assertEquals("retry", views.detail(state, false, "").get("pauseKind"));
    }

    @Test
    void theAuditVerdictIsCachedUntilTheChainGrows() throws Exception {
        when(trail.recentEvents(ID)).thenReturn(List.of(event(3, "TASK_DONE", "understand:hash")));
        when(trail.timeline(ID)).thenReturn(List.of(new AuditEvent(3, START, "TASK_DONE", "understand:hash")));
        views.detail(state, false, "");
        views.detail(state, false, "");
        verify(runs, times(1)).auditValid(ID);
        when(trail.recentEvents(ID)).thenReturn(List.of(event(4, "RUN_COMPLETED", "All tasks passed")));
        views.detail(state, false, "");
        verify(runs, times(2)).auditValid(ID);
    }

    @Test
    void aRunWhoseScenarioFileIsGoneIsReportedAsNotFound() throws Exception {
        Files.delete(Path.of(state.specPath));
        assertThrows(NotFoundException.class, () -> views.detail(state, false, ""));
    }

    @Test
    void artifactsAreServedOnlyByValidatedRunIdAndPlainFileName() throws Exception {
        evidence("plan-v1.txt", "The plan");
        Files.writeString(root.resolve("evidence").resolve("secret.txt"), "outside any run");
        assertEquals("The plan", views.artifact(ID, "plan-v1.txt"));
        assertThrows(NotFoundException.class, () -> views.artifact(ID, "missing-v1.txt"));
        for (String name : new String[] {"../secret.txt", "plan-v1.TXT", "plan v1.txt", "", null, "a/b.txt"}) {
            assertThrows(IllegalArgumentException.class, () -> views.artifact(ID, name), String.valueOf(name));
        }
        assertThrows(IllegalArgumentException.class, () -> views.artifact("../" + ID, "plan-v1.txt"));
        Files.createSymbolicLink(
                root.resolve("evidence").resolve(ID).resolve("link-v1.txt"), root.resolve("evidence/secret.txt"));
        assertThrows(NotFoundException.class, () -> views.artifact(ID, "link-v1.txt"), "Symlinks are never followed");
    }

    @Test
    void metricsSeparateFixtureAndLiveRunsAndReportNothingForEmptySamples() throws Exception {
        RunState completed = new RunState("00000000-0000-0000-0000-0000000000a1", "demo", "hash");
        completed.mode = "fixture";
        completed.status = RunStatus.COMPLETED;
        completed.startedAt = START;
        completed.finishedAt = START.plusSeconds(20);
        RunState failed = new RunState("00000000-0000-0000-0000-0000000000a2", "demo", "hash");
        failed.mode = "fixture";
        failed.status = RunStatus.FAILED;
        failed.startedAt = START;
        failed.finishedAt = START.plusSeconds(40);
        state.status = RunStatus.PAUSED;
        when(runs.recentRuns()).thenReturn(List.of(completed, failed, state));
        when(trail.recentTimelines(any()))
                .thenReturn(Map.of(
                        completed.id,
                        List.of(
                                new AuditEvent(1, START.plusSeconds(1), "RETRY_AVAILABLE", "apply:apply-error-v1"),
                                new AuditEvent(2, START.plusSeconds(2), "TASK_STARTED", "apply"),
                                new AuditEvent(3, START.plusSeconds(7), "TASK_DONE", "apply:hash"),
                                new AuditEvent(4, START.plusSeconds(8), "PARTIAL_REPLAN", "stale=[x]"))));
        Map<String, Object> metrics = views.metrics();
        assertEquals(List.of("sample", "fixture", "live"), List.copyOf(metrics.keySet()));
        assertTrue(metrics.get("sample").toString().startsWith("Latest 100 updated runs"));

        var fixture = (Map<?, ?>) metrics.get("fixture");
        assertEquals(3, fixture.get("runs"));
        assertEquals(2L, fixture.get("terminalRuns"));
        assertEquals(1L, fixture.get("completedRuns"));
        assertEquals(0.5, fixture.get("completionRate"));
        assertEquals(Map.of("COMPLETED", 1L, "FAILED", 1L, "PAUSED", 1L), fixture.get("outcomes"));
        assertEquals(1, fixture.get("retryExecutions"));
        assertEquals(1, fixture.get("rollbacks"));
        assertEquals(1.0 / 3, fixture.get("retryRunRate"));
        assertEquals(1.0 / 3, fixture.get("rollbackRunRate"));
        assertEquals(30_000.0, fixture.get("meanTerminalLatencyMillis"));
        assertEquals(1, fixture.get("recoveredTasks"));
        assertEquals(6_000L, fixture.get("meanRecoveryMillis"));

        var live = (Map<?, ?>) metrics.get("live");
        assertEquals(0, live.get("runs"));
        for (String rate : List.of(
                "completionRate",
                "retryRunRate",
                "rollbackRunRate",
                "meanTerminalLatencyMillis",
                "meanRecoveryMillis")) {
            assertNull(live.get(rate), rate + " of an empty sample is unknown, not zero");
        }
        assertEquals(3, views.runs().size());
    }
}
