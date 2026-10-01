package dev.softwarefactory.run;

import static dev.softwarefactory.run.RunEngineFixture.LIVE_ENVIRONMENT;
import static dev.softwarefactory.run.RunEngineFixture.PATCH;
import static dev.softwarefactory.run.RunEngineFixture.artifact;
import static dev.softwarefactory.run.RunEngineFixture.patch;
import static dev.softwarefactory.run.RunEngineFixture.validate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.softwarefactory.generation.AgentRuntimes;
import dev.softwarefactory.platform.WorkflowConflictException;
import dev.softwarefactory.scenario.Stage;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Starting runs, live generation through an injected agent, parallel branches and recovery. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RunEngineGenerationTest {
    @TempDir
    Path root;

    private RunEngineFixture fixture;
    private InMemoryRunStore store;

    @BeforeEach
    void setup() throws Exception {
        fixture = new RunEngineFixture(root);
        store = fixture.store;
        fixture.fixture("output.txt", "recorded artifact");
        fixture.fixture("change.patch", PATCH);
    }

    private static TaskSpec live(String id, List<String> dependencies, String role, String prompt) {
        return new TaskSpec(
                id,
                Stage.REQUIREMENTS,
                dependencies,
                TaskKind.ARTIFACT,
                role,
                prompt,
                null,
                List.of(),
                List.of(),
                false);
    }

    @Test
    void startPinsBaselineHashesAndBudgetWithoutGeneratingAnything() throws Exception {
        RunEngine engine = fixture.engine();
        RunState state = fixture.start(engine, RunMode.FIXTURE, List.of(artifact("write", List.of(), "output.txt")));
        assertEquals(RunStatus.CREATED, state.status);
        assertEquals(RunEngineFixture.NOW, state.startedAt);
        assertEquals(RunMode.FIXTURE, state.mode);
        assertEquals(24, state.maxModelCalls);
        assertEquals(40, state.baselineCommit.length(), "The tag is resolved to an immutable commit");
        assertEquals(Map.of("write", TaskStatus.PENDING), state.tasks);
        assertTrue(Files.isDirectory(Path.of(state.candidatePath)));
        assertEquals(List.of("fixture:workflow-test"), store.details("RUN_CREATED"));
        assertFalse(Files.exists(root.resolve("evidence")));
    }

    @Test
    void startRefusesUnknownModesUnconfiguredLiveRunsAndIncompleteScenarios() throws Exception {
        RunEngine engine = fixture.engine();
        var tasks = List.of(artifact("write", List.of(), "output.txt"));
        assertThrows(IllegalArgumentException.class, () -> fixture.start(engine, "dry-run", tasks));
        assertThrows(WorkflowConflictException.class, () -> fixture.start(engine, RunMode.LIVE, tasks));
        fixture.writeScenario("  ", tasks);
        assertThrows(IllegalArgumentException.class, () -> engine.start(fixture.scenarioFile(), RunMode.FIXTURE));
        assertThrows(IllegalArgumentException.class, () -> fixture.start(engine, RunMode.FIXTURE, List.of()));
        assertEquals(0, store.eventCount());
    }

    @Test
    void liveGenerationReceivesGovernedInputsAndIsChargedToTheRunBudget() throws Exception {
        Map<String, String> prompts = new ConcurrentHashMap<>();
        AgentRuntimes agents = (checkout, beforeRequest, audit) -> (role, prompt) -> {
            beforeRequest.reserve();
            audit.record("TOOL_STARTED", "read_file:inputSha256=abc");
            prompts.put(role, prompt);
            assertTrue(Files.isDirectory(checkout), "The agent reads the candidate, not the checkout");
            return "output of " + role;
        };
        RunEngine engine = fixture.engine(store, LIVE_ENVIRONMENT, agents);
        RunState state = fixture.start(
                engine,
                RunMode.LIVE,
                List.of(
                        live("understand", List.of(), "requirements", "Define the criteria"),
                        live("handoff", List.of("understand"), "implementer", "Describe the edits"),
                        live("test-plan", List.of("handoff"), "test_author", "Plan the tests")));
        state = engine.advance(state.id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertEquals(3, state.modelCalls);
        assertEquals(List.of("understand:1/24", "handoff:2/24", "test-plan:3/24"), store.details("MODEL_CALL_STARTED"));
        assertEquals(3, store.details("TOOL_STARTED").size());
        assertEquals("output of requirements", Files.readString(fixture.evidence(state, "understand-v1")));

        String handoff = prompts.get("implementer");
        assertTrue(handoff.startsWith("Requirement: Test governance\n\nTask: Describe the edits"));
        assertTrue(handoff.contains("This is a handoff artifact"));
        assertTrue(handoff.contains("Input artifact understand sha256=" + state.artifactHashes.get("understand")));
        assertTrue(handoff.contains("output of requirements"));
        assertTrue(handoff.contains("--- README.md ---") || handoff.contains("Repository source"));
        assertTrue(prompts.get("test_author").contains("independent test plan"));
        assertFalse(prompts.get("requirements").contains("Input artifact"));
    }

    @Test
    void anExhaustedModelBudgetSafeStopsTheRun() throws Exception {
        AgentRuntimes agents = (checkout, beforeRequest, audit) -> (role, prompt) -> {
            beforeRequest.reserve();
            beforeRequest.reserve();
            return "never returned";
        };
        var environment = new HashMap<>(LIVE_ENVIRONMENT);
        environment.put("FACTORY_MAX_MODEL_CALLS", "1");
        RunEngine engine = fixture.engine(store, environment, agents);
        RunState state = fixture.start(
                engine, RunMode.LIVE, List.of(live("understand", List.of(), "requirements", "Define the criteria")));
        state = engine.advance(state.id);
        assertEquals(RunStatus.SAFE_STOPPED, state.status);
        assertEquals(1, state.modelCalls);
        assertEquals(List.of("understand:Model call budget exhausted"), store.details("POLICY_SAFE_STOP"));
    }

    @Test
    void aFailedLiveAttemptIsRetriedWithItsDiagnosticAndTheOperatorFeedback() throws Exception {
        Map<String, String> prompts = new ConcurrentHashMap<>();
        AtomicBoolean failed = new AtomicBoolean();
        AgentRuntimes agents = (checkout, beforeRequest, audit) -> (role, prompt) -> {
            if (failed.compareAndSet(false, true)) throw new IOException("provider returned 529");
            prompts.put(role, prompt);
            return PATCH;
        };
        RunEngine engine = fixture.engine(store, LIVE_ENVIRONMENT, agents);
        var apply = new TaskSpec(
                "apply",
                Stage.IMPLEMENTATION,
                List.of(),
                TaskKind.PATCH,
                "implementer",
                "Change the README",
                null,
                List.of(),
                List.of("README.md"),
                false);
        RunState state = fixture.start(engine, RunMode.LIVE, List.of(apply, validate("validate", List.of("apply"))));
        state = engine.advance(state.id);
        assertEquals(1, state.attempts.get("apply"));
        state = engine.revise(state.id, "apply", "Keep it to one line.");
        state = engine.advance(state.id);
        assertEquals(RunStatus.COMPLETED, state.status);
        String prompt = prompts.get("implementer");
        assertTrue(prompt.contains("Operator review feedback to address:\nKeep it to one line."));
        assertFalse(prompt.contains("Previous attempt failed"), "A revision starts from a clean retry budget");
        assertTrue(prompt.contains("Return only a git apply-compatible unified diff"));
        assertTrue(prompt.contains("[README.md]"));
        assertEquals(PATCH, Files.readString(fixture.evidence(state, "apply-proposal-v1")));
    }

    @Test
    void aRetryCarriesThePreviousDiagnosticIntoTheNextPrompt() throws Exception {
        Map<String, String> prompts = new ConcurrentHashMap<>();
        AtomicBoolean failed = new AtomicBoolean();
        AgentRuntimes agents = (checkout, beforeRequest, audit) -> (role, prompt) -> {
            if (failed.compareAndSet(false, true)) throw new IOException("provider returned 529");
            prompts.put(role, prompt);
            return "second attempt";
        };
        RunEngine engine = fixture.engine(store, LIVE_ENVIRONMENT, agents);
        RunState state = fixture.start(
                engine, RunMode.LIVE, List.of(live("understand", List.of(), "requirements", "Define the criteria")));
        state = engine.advance(state.id);
        assertEquals(RunStatus.PAUSED, state.status);
        state = engine.advance(state.id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertTrue(prompts.get("requirements")
                .contains("Previous attempt failed. Correct this diagnostic without weakening policy:\n"
                        + "java.io.IOException: provider returned 529"));
    }

    @Test
    void parallelBranchesJoinBeforeDownstreamWork() throws Exception {
        RunEngine engine = fixture.engine();
        RunState state = fixture.start(
                engine,
                RunMode.FIXTURE,
                List.of(
                        artifact("architecture", List.of(), "output.txt"),
                        artifact("risk", List.of(), "output.txt"),
                        artifact("plan", List.of("architecture", "risk"), "output.txt")));
        state = engine.advance(state.id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertEquals(List.of("architecture,risk"), store.details("PARALLEL_JOIN"));
        assertEquals(3, store.details("TASK_DONE").size());
        assertEquals(List.of("architecture", "risk", "plan"), store.details("TASK_STARTED"));
    }

    @Test
    void aPolicyStopOnOneBranchCancelsItsSibling() throws Exception {
        CountDownLatch siblingStarted = new CountDownLatch(1);
        AtomicBoolean siblingInterrupted = new AtomicBoolean();
        AgentRuntimes agents = (checkout, beforeRequest, audit) -> (role, prompt) -> {
            if ("risk".equals(role)) {
                try {
                    siblingStarted.await();
                } catch (InterruptedException interrupted) {
                    throw new IOException(interrupted);
                }
                throw new SecurityException("Provider returned a tool call after tool access was disabled");
            }
            siblingStarted.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(25));
            } catch (InterruptedException cancelled) {
                siblingInterrupted.set(true);
                throw new IOException("cancelled", cancelled);
            }
            return "too late";
        };
        RunEngine engine = fixture.engine(store, LIVE_ENVIRONMENT, agents);
        RunState state = fixture.start(
                engine,
                RunMode.LIVE,
                List.of(
                        live("architecture", List.of(), "architecture", "Design"),
                        live("risk", List.of(), "risk", "Assess")));
        state = engine.advance(state.id);
        assertEquals(RunStatus.SAFE_STOPPED, state.status);
        assertTrue(siblingInterrupted.get(), "The run has ended, so the sibling branch is not paid for");
        assertEquals(TaskStatus.FAILED, state.tasks.get("risk"));
        assertEquals(TaskStatus.FAILED, state.tasks.get("architecture"));
        assertEquals(2, store.details("POLICY_SAFE_STOP").size());
        assertFalse(store.has("TASK_DONE"));
        assertEquals(List.of("architecture,risk"), store.details("PARALLEL_JOIN"));
    }

    @Test
    void anInterruptedPatchResetsTheCandidateAndRepeatsDownstreamValidation() throws Exception {
        RunEngine engine = fixture.engine();
        RunState state = fixture.start(
                engine,
                RunMode.FIXTURE,
                List.of(patch("apply", List.of(), "change.patch", false), validate("validate", List.of("apply"))));
        state = engine.advance(state.id);
        assertEquals(RunStatus.COMPLETED, state.status);
        int appliedVersion = state.artifactVersions.get("apply");

        // Reconstruct the snapshot persisted at PATCH_STARTED: the output is on disk, the task not yet done.
        state.status = RunStatus.RUNNING;
        state.finishedAt = null;
        state.tasks.put("apply", TaskStatus.RUNNING);
        state.artifactVersions.put("apply", appliedVersion - 1);
        state.artifactHashes.remove("apply");
        state.attempts.put("validate", 1);
        store.record(state, "TEST_INTERRUPTION", "patch checkpoint");
        Files.writeString(Path.of(state.candidatePath).resolve("README.md"), "half-applied\n");

        state = engine.advance(state.id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertEquals(appliedVersion, state.artifactVersions.get("apply"), "The finished patch output is adopted");
        assertEquals("reviewed change\n", fixture.candidateFile(state, "README.md"));
        assertFalse(state.attempts.containsKey("validate"), "Invalidated downstream tasks get a fresh retry budget");
        assertEquals(2, fixture.validator.requiredTestClasses.size(), "Validation runs again on the rebuilt candidate");
        assertEquals(List.of("interrupted=[apply]; invalidated=[validate]"), store.details("RUN_RECOVERING"));
        assertEquals(List.of("interrupted=[apply]; candidateReset=true"), store.details("RUN_RECOVERED"));
    }

    @Test
    void aPatchOutsideItsWriteScopeSafeStopsAndIsKeptAsEvidence() throws Exception {
        fixture.fixture("escape.patch", PATCH.replace("README.md", "pom.xml"));
        RunEngine engine = fixture.engine();
        RunState state =
                fixture.start(engine, RunMode.FIXTURE, List.of(patch("apply", List.of(), "escape.patch", false)));
        state = engine.advance(state.id);
        assertEquals(RunStatus.SAFE_STOPPED, state.status);
        assertEquals(List.of("apply:Patch outside approved scope: pom.xml"), store.details("POLICY_SAFE_STOP"));
        assertTrue(
                Files.exists(fixture.evidence(state, "apply-proposal-v1")), "The refused proposal stays inspectable");
        assertEquals("original\n", fixture.candidateFile(state, "README.md"));
    }

    @Test
    void aPatchThatDoesNotApplyIsRetriedThenFailsTheRun() throws Exception {
        fixture.fixture("stale.patch", PATCH.replace("-original", "-not the baseline"));
        RunEngine engine = fixture.engine();
        RunState state =
                fixture.start(engine, RunMode.FIXTURE, List.of(patch("apply", List.of(), "stale.patch", false)));
        state = engine.advance(state.id);
        assertEquals(RunStatus.PAUSED, state.status);
        state = engine.advance(state.id);
        assertEquals(RunStatus.FAILED, state.status);
        assertTrue(Files.exists(fixture.evidence(state, "apply-proposal-v1")));
        assertTrue(Files.exists(fixture.evidence(state, "apply-proposal-v2")), "Each attempt keeps its own proposal");
        assertEquals(List.of("apply:apply-error-v2"), store.details("TASK_FAILED"));
    }

    @Test
    void aPatchMissingItsFinalNewlineStillApplies() throws Exception {
        // A model reply that stops right after the last content line is a well-formed diff with no
        // file-terminating newline. git rejects that as a corrupt patch; the engine must normalize it first.
        fixture.fixture("no-trailing-newline.patch", PATCH.stripTrailing());
        RunEngine engine = fixture.engine();
        RunState state = fixture.start(
                engine, RunMode.FIXTURE, List.of(patch("apply", List.of(), "no-trailing-newline.patch", false)));
        state = engine.advance(state.id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertEquals("reviewed change\n", fixture.candidateFile(state, "README.md"));
    }

    @Test
    void aPatchWrappedInAMarkdownFenceStillApplies() throws Exception {
        // Despite being told not to, a model sometimes wraps its whole reply in a code fence. git apply
        // does not reject that cleanly: it can silently stop partway through the hunk instead. The fence
        // must come off before anything reaches git.
        fixture.fixture("fenced.patch", "```diff\n" + PATCH + "```\n");
        RunEngine engine = fixture.engine();
        RunState state =
                fixture.start(engine, RunMode.FIXTURE, List.of(patch("apply", List.of(), "fenced.patch", false)));
        state = engine.advance(state.id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertEquals("reviewed change\n", fixture.candidateFile(state, "README.md"));
    }

    @Test
    void unfinishedValidationsAreReportedUntilTheyPass() throws Exception {
        RunEngine engine = fixture.engine();
        var tasks = List.of(patch("apply", List.of(), "change.patch", false), validate("validate", List.of("apply")));
        RunState state = fixture.start(engine, RunMode.FIXTURE, tasks);
        var spec = new dev.softwarefactory.scenario.ScenarioSpec("s", "r", "b", tasks);
        assertEquals(List.of("validate"), RunEngine.unfinishedValidations(state, spec));
        assertEquals(List.of(), RunEngine.unfinishedValidations(engine.advance(state.id), spec));
        assertTrue(engine.validatorStatus().ready());
        assertEquals(0, engine.sweepOrphanedValidatorContainers(true));
    }
}
