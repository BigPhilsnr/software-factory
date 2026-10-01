package dev.softwarefactory.operator.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.softwarefactory.audit.AuditEvent;
import dev.softwarefactory.audit.AuditTrail;
import dev.softwarefactory.candidate.CommandRunner;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.platform.Json;
import dev.softwarefactory.run.DurableRunStore;
import dev.softwarefactory.run.RunEngine;
import dev.softwarefactory.run.RunState;
import dev.softwarefactory.run.RunStatus;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.Stage;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliActionsTest {
    private static final String ID = "00000000-0000-0000-0000-0000000000ee";

    @TempDir
    Path root;

    private final RunEngine engine = mock(RunEngine.class);
    private final DurableRunStore runs = mock(DurableRunStore.class);
    private final AuditTrail trail = mock(AuditTrail.class);
    private CliActions actions;
    private RunState state;

    @BeforeEach
    void setup() throws Exception {
        actions = new CliActions(engine, runs, trail, root);
        var apply = new TaskSpec(
                "apply",
                Stage.IMPLEMENTATION,
                List.of(),
                TaskKind.PATCH,
                "implementer",
                "Change",
                null,
                List.of(),
                List.of("shortener/src/main"),
                true);
        Path scenario = root.resolve("scenario.json");
        Files.writeString(
                scenario, Json.MAPPER.writeValueAsString(new ScenarioSpec("demo", "Add expiry", "v1", List.of(apply))));
        state = new RunState(ID, "demo", "hash");
        state.specPath = scenario.toString();
        state.baselineTag = "v1";
        state.startedAt = Instant.parse("2026-01-01T00:00:00Z");
        when(runs.load(ID)).thenReturn(state);
    }

    private void git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        CommandRunner.checked(root, command, Duration.ofSeconds(10));
    }

    @Test
    void transitionsGoStraightToTheEngine() throws Exception {
        Path feedback = root.resolve("feedback.txt");
        Files.writeString(feedback, "Smaller change");
        actions.start(Path.of("scenario.json"), "fixture");
        actions.advance(ID);
        actions.decide(ID, "abc", true);
        actions.clarify(ID, "One region");
        actions.revise(ID, "apply", feedback);
        actions.revise(ID, "apply", null);
        verify(engine).start(Path.of("scenario.json"), "fixture");
        verify(engine).advance(ID);
        verify(engine).approve(ID, "abc", true);
        verify(engine).clarify(ID, "One region");
        verify(engine).revise(ID, "apply", "Smaller change");
        verify(engine).revise(ID, "apply", null);
    }

    @Test
    void statusMetricsAndAuditVerdictAreReadFromTheRecord() throws Exception {
        state.finishedAt = state.startedAt.plusSeconds(90);
        when(trail.timeline(ID))
                .thenReturn(List.of(new AuditEvent(1, state.startedAt, "APPROVAL_REQUIRED", "apply:hash")));
        when(runs.auditValid(ID)).thenReturn(true, false);
        assertEquals(state, actions.status(ID));
        var metrics = actions.metrics(ID);
        assertEquals(90_000, metrics.elapsedMillis());
        assertEquals(1, metrics.approvalRequests());
        assertEquals("AUDIT_VALID", actions.verifyAudit(ID));
        assertEquals("AUDIT_INVALID", actions.verifyAudit(ID));
    }

    @Test
    void reviewNamesTheProposalFileAndTheExactHashToApprove() throws Exception {
        assertThrows(IllegalStateException.class, () -> actions.review(ID), "Nothing is pending yet");
        state.pendingApprovalTask = "apply";
        state.pendingApprovalHash = "f".repeat(64);
        state.artifactVersions.put("apply", 1);
        Map<String, Object> review = actions.review(ID);
        assertEquals("none", review.get("proposal"));
        assertEquals("v1", review.get("baselineCommit"), "Runs created before commits were pinned show their tag");
        assertEquals("not yet validated", review.get("validatedCandidateHash"));

        Path proposal = root.resolve("evidence").resolve(ID).resolve("apply-v1.txt");
        Files.createDirectories(proposal.getParent());
        Files.writeString(proposal, "diff");
        state.baselineCommit = "abc123";
        state.validatedCandidateHash = "e".repeat(64);
        review = actions.review(ID);
        assertEquals(proposal.toString(), review.get("proposal"));
        assertEquals("abc123", review.get("baselineCommit"));
        assertEquals("f".repeat(64), review.get("reviewedHash"));
        assertEquals(List.of("shortener/src/main"), review.get("writeScope"));
        assertEquals("e".repeat(64), review.get("validatedCandidateHash"));

        state.pendingApprovalTask = "removed-task";
        assertThrows(IllegalStateException.class, () -> actions.review(ID));
    }

    @Test
    void pruneRemovesCandidatesAndEvidenceOfOldTerminalRunsOnlyWhenApplied() throws Exception {
        Files.writeString(root.resolve("README.md"), "baseline\n");
        git("init", "-q");
        git("add", "README.md");
        git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "baseline");
        Instant longAgo = Instant.now().minus(Duration.ofDays(60));
        RunState finished = oldRun(RunStatus.COMPLETED, longAgo);
        finished.candidatePath =
                new GitWorkspace(root).create(finished.id, "HEAD").toString();
        Path evidence = Files.createDirectories(root.resolve("evidence").resolve(finished.id));
        Files.writeString(evidence.resolve("plan-v1.txt"), "plan");
        RunState paused = oldRun(RunStatus.PAUSED, null);
        RunState recent = oldRun(RunStatus.FAILED, Instant.now());
        RunState nothingLeft = oldRun(RunStatus.FAILED, longAgo);
        when(runs.runsUpdatedBefore(any())).thenReturn(List.of(finished, paused, recent, nothingLeft));

        Map<String, Object> listed = actions.prune(30, false);
        assertEquals(false, listed.get("applied"));
        var entries = (List<?>) listed.get("runs");
        assertEquals(1, entries.size(), "Only old terminal runs with something left are prunable");
        var entry = (Map<?, ?>) entries.getFirst();
        assertEquals(finished.id, entry.get("id"));
        assertEquals("COMPLETED", entry.get("status"));
        assertEquals(finished.candidatePath, entry.get("candidate"));
        assertEquals(evidence.toString(), entry.get("evidence"));
        assertTrue(Files.isDirectory(evidence) && Files.isDirectory(Path.of(finished.candidatePath)));

        Map<String, Object> applied = actions.prune(30, true);
        assertEquals(true, applied.get("applied"));
        assertFalse(Files.exists(evidence));
        assertFalse(Files.exists(Path.of(finished.candidatePath)));

        var afterwards = (List<?>) actions.prune(30, true).get("runs");
        assertTrue(afterwards.isEmpty(), "A pruned run has nothing left to remove");
    }

    @Test
    void pruneNeverTouchesDirectoriesTheFactoryDoesNotOwn() throws Exception {
        RunState foreign = oldRun(RunStatus.SAFE_STOPPED, Instant.now().minus(Duration.ofDays(60)));
        Path outside = Files.createDirectories(root.resolve("not-a-run-workspace"));
        foreign.candidatePath = outside.toString();
        Path evidence = Files.createDirectories(root.resolve("evidence").resolve(foreign.id));
        when(runs.runsUpdatedBefore(any())).thenReturn(List.of(foreign));
        git("init", "-q");
        var entry = (Map<?, ?>) ((List<?>) actions.prune(30, true).get("runs")).getFirst();
        assertNull(entry.get("candidate"));
        assertTrue(Files.isDirectory(outside));
        assertFalse(Files.exists(evidence));
    }

    private static RunState oldRun(RunStatus status, Instant finishedAt) {
        RunState run = new RunState(UUID.randomUUID().toString(), "demo", "hash");
        run.status = status;
        run.finishedAt = finishedAt;
        return run;
    }
}
