package dev.softwarefactory.run;

import static dev.softwarefactory.run.RunEngineFixture.PATCH;
import static dev.softwarefactory.run.RunEngineFixture.artifact;
import static dev.softwarefactory.run.RunEngineFixture.clarify;
import static dev.softwarefactory.run.RunEngineFixture.patch;
import static dev.softwarefactory.run.RunEngineFixture.validate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.softwarefactory.generation.ModelClients;
import dev.softwarefactory.platform.FactorySettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real workflow and Git/evidence behavior with an in-memory store; no Docker, DB or provider. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RunEngineBehaviorTest {
    @TempDir
    Path root;

    private RunEngineFixture fixture;
    private InMemoryRunStore store;
    private RunEngine engine;

    @BeforeEach
    void setup() throws Exception {
        fixture = new RunEngineFixture(root);
        store = fixture.store;
        engine = fixture.engine();
    }

    @Test
    void retriesOnceThenStopsWithoutExecutingDownstreamWork() throws Exception {
        var state = start(List.of(
                artifact("generate", List.of(), "missing.txt"),
                artifact("dependent", List.of("generate"), "output.txt")));
        state = engine.advance(state.id);
        assertEquals(RunStatus.PAUSED, state.status);
        assertEquals(1, state.attempts.get("generate"));
        assertEquals(TaskStatus.PENDING, state.tasks.get("dependent"));
        assertTrue(
                Files.readString(fixture.evidence(state, "generate-error-v1")).contains("NoSuchFileException"));
        state = engine.advance(state.id);
        assertEquals(RunStatus.FAILED, state.status);
        assertEquals(2, state.attempts.get("generate"));
        assertEquals(RunEngineFixture.NOW, state.finishedAt);
        int events = store.eventCount();
        assertEquals(RunStatus.FAILED, engine.advance(state.id).status);
        assertEquals(events, store.eventCount());
        assertTrue(store.has("RETRY_AVAILABLE"));
        assertTrue(store.has("TASK_FAILED"));
    }

    @Test
    void staleApprovalCannotApplyPatchAndExactApprovalCan() throws Exception {
        var state = proposal();
        String id = state.id;
        assertThrows(IllegalStateException.class, () -> engine.approve(id, "0".repeat(64), true));
        assertEquals(state.pendingApprovalHash, store.load(id).pendingApprovalHash);
        assertEquals("original\n", fixture.candidateFile(state, "README.md"));
        engine.approve(id, state.pendingApprovalHash, true);
        assertEquals(RunStatus.COMPLETED, engine.advance(id).status);
        assertEquals("reviewed change\n", fixture.candidateFile(state, "README.md"));
        assertEquals("original\n", Files.readString(root.resolve("README.md")));
    }

    @Test
    void changedProposalRequiresFreshApprovalBeforeApplication() throws Exception {
        var state = proposal();
        engine.approve(state.id, state.pendingApprovalHash, true);
        Files.writeString(fixture.evidence(state, "apply-v1"), PATCH.replace("reviewed change", "different change"));
        state = engine.advance(state.id);
        assertEquals(RunStatus.PAUSED, state.status);
        assertEquals("apply", state.pendingApprovalTask);
        assertNotEquals(state.approvals.get("apply"), state.pendingApprovalHash);
        assertEquals("original\n", fixture.candidateFile(state, "README.md"));
    }

    @Test
    void parallelPolicyFailureRemainsTerminalWhenAnotherBranchFails() throws Exception {
        for (boolean securityFirst : List.of(true, false)) {
            var policy = artifact("policy", List.of(), "../outside.txt");
            var transientFailure = artifact("transient", List.of(), "missing.txt");
            var tasks = new ArrayList<>(
                    securityFirst ? List.of(policy, transientFailure) : List.of(transientFailure, policy));
            tasks.add(artifact("join", List.of("policy", "transient"), "output.txt"));
            var state = engine.advance(start(tasks).id);
            assertEquals(RunStatus.SAFE_STOPPED, state.status);
            assertEquals(TaskStatus.FAILED, state.tasks.get("policy"));
            assertEquals(TaskStatus.PENDING, state.tasks.get("join"));
            assertNotNull(state.finishedAt);
            assertTrue(store.has("PARALLEL_JOIN"));
        }
    }

    @Test
    void completedEvidenceTamperStopsBeforeClarificationCanAdvance() throws Exception {
        fixture.fixture("output.txt", "original artifact");
        var state = engine.advance(start(List.of(
                        artifact("understand", List.of(), "output.txt"), clarify("clarify", List.of("understand"))))
                .id);
        assertEquals("clarify", state.pendingClarificationTask);
        Files.writeString(fixture.evidence(state, "understand-v1"), "tampered");
        assertEquals(RunStatus.SAFE_STOPPED, engine.advance(state.id).status);
        assertTrue(store.has("POLICY_SAFE_STOP"));
    }

    @Test
    void interruptedArtifactIsRecoveredWithoutRegenerationAndLeaseBlocksConcurrentAdvance() throws Exception {
        var state = start(List.of(artifact("generate", List.of(), "missing.txt")));
        state.tasks.put("generate", TaskStatus.RUNNING);
        store.record(state, "TEST_INTERRUPTION", "persisted task start");
        Path output = fixture.evidence(state, "generate-v1");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "completed before the process stopped");
        String id = state.id;
        try (var ignored = store.lease(id)) {
            assertThrows(IllegalStateException.class, () -> engine.advance(id));
        }
        state = engine.advance(id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertEquals(1, state.artifactVersions.get("generate"));
        assertTrue(state.attempts.isEmpty());
        assertTrue(store.has("RUN_RECOVERED"));
    }

    @Test
    void failedStartCleansOnlyCandidatesConfirmedAbsentFromPersistence() {
        for (boolean commitBeforeFailure : List.of(false, true)) {
            var captured = new AtomicReference<RunState>();
            RunStore failing = new RunStore() {
                @Override
                public RunState load(String id) {
                    if (!commitBeforeFailure) throw new RunStore.MissingRunException(id);
                    return captured.get();
                }

                @Override
                public Lease lease(String id) {
                    return () -> {};
                }

                @Override
                public boolean auditValid(String id) {
                    return true;
                }

                @Override
                public void record(RunState state, String type, String detail) throws IOException {
                    captured.set(state);
                    throw new IOException("Lost persistence acknowledgement");
                }
            };
            engine = fixture.engine(failing, Map.of(), RunEngineFixture.unusedAgents());
            assertThrows(IOException.class, () -> start(List.of(artifact("generate", List.of(), "missing.txt"))));
            assertEquals(commitBeforeFailure, Files.exists(Path.of(captured.get().candidatePath)));
        }
    }

    @Test
    void revisionResetsTheRetryBudgetWithoutReusingDiagnosticEvidence() throws Exception {
        var state = engine.advance(start(List.of(artifact("generate", List.of(), "missing.txt"))).id);
        assertEquals(1, state.attempts.get("generate"));
        state = engine.revise(state.id, "generate", "Use the corrected fixture.");
        assertTrue(state.attempts.isEmpty(), "Invalidated tasks get a fresh retry budget");
        state = engine.advance(state.id);
        assertEquals(RunStatus.PAUSED, state.status, "One failure after revision is still retryable");
        assertEquals(1, state.attempts.get("generate"));
        assertTrue(Files.exists(fixture.evidence(state, "generate-error-v2")));
    }

    @Test
    void productionWiringPausesWhenTheSandboxPlatformIsUnavailable() throws Exception {
        var settings = FactorySettings.from(Map.of(
                "FACTORY_MAVEN_REPOSITORY", root.resolve("missing-maven-cache").toString()));
        try (var clients = new ModelClients(settings)) {
            engine = new RunEngine(store, root, settings, clients);
            assertFalse(engine.validatorStatus().ready());
            fixture.fixture("change.patch", PATCH);
            String id = start(List.of(
                            patch("apply", List.of(), "change.patch", false), validate("validate", List.of("apply"))))
                    .id;
            for (int advance = 0; advance < 3; advance++) {
                var state = engine.advance(id);
                assertEquals(RunStatus.PAUSED, state.status);
                assertEquals(TaskStatus.PENDING, state.tasks.get("validate"));
                assertTrue(state.attempts.isEmpty(), "Platform failures must not consume the candidate's retries");
            }
            assertTrue(store.has("INFRASTRUCTURE_UNAVAILABLE"));
            assertFalse(store.has("RETRY_AVAILABLE"));
        }
    }

    private RunState proposal() throws Exception {
        fixture.fixture("change.patch", PATCH);
        var state = engine.advance(start(List.of(patch("apply", List.of(), "change.patch", true))).id);
        assertEquals(RunStatus.PAUSED, state.status);
        assertNotNull(state.pendingApprovalHash);
        return state;
    }

    private RunState start(List<dev.softwarefactory.scenario.TaskSpec> tasks) throws IOException, InterruptedException {
        return fixture.start(engine, RunMode.FIXTURE, tasks);
    }
}
