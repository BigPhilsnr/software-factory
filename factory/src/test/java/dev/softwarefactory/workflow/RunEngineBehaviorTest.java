package dev.softwarefactory.workflow;

import dev.softwarefactory.execution.GitWorkspace;
import dev.softwarefactory.persistence.RunStore;
import dev.softwarefactory.serialization.Json;
import dev.softwarefactory.workflow.scenario.ScenarioSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real workflow and Git/evidence behavior with an in-memory store; no Docker, DB or provider. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RunEngineBehaviorTest {
    @TempDir Path root;
    private final MemoryStore store = new MemoryStore();
    private RunEngine engine;
    private static final String PATCH = """
        diff --git a/README.md b/README.md
        --- a/README.md
        +++ b/README.md
        @@ -1 +1 @@
        -original
        +reviewed change
        """;

    @BeforeEach void setup() throws Exception {
        Files.writeString(root.resolve("README.md"), "original\n");
        git("init", "-q");
        git("add", "README.md");
        git("-c", "user.name=Workflow Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "baseline");
        git("tag", "test-baseline");
        engine = new RunEngine(store, root);
    }

    @Test void retriesOnceThenStopsWithoutExecutingDownstreamWork() throws Exception {
        var state = start(List.of(artifact("generate", List.of(), "missing.txt"), artifact("dependent", List.of("generate"), "output.txt")));
        state = engine.advance(state.id);
        assertEquals(RunStatus.PAUSED, state.status);
        assertEquals(1, state.attempts.get("generate"));
        assertEquals(TaskStatus.PENDING, state.tasks.get("dependent"));
        assertTrue(Files.readString(root.resolve("evidence/" + state.id + "/generate-error-v1.txt")).contains("NoSuchFileException"));
        state = engine.advance(state.id);
        assertEquals(RunStatus.FAILED, state.status);
        assertEquals(2, state.attempts.get("generate"));
        int events = store.events.size();
        assertEquals(RunStatus.FAILED, engine.advance(state.id).status);
        assertEquals(events, store.events.size());
        assertTrue(store.has("RETRY_AVAILABLE"));
        assertTrue(store.has("TASK_FAILED"));
    }

    @Test void staleApprovalCannotApplyPatchAndExactApprovalCan() throws Exception {
        var state = proposal();
        String id = state.id;
        assertThrows(IllegalArgumentException.class, () -> engine.approve(id, "0".repeat(64), true));
        assertEquals(state.pendingApprovalHash, store.load(id).pendingApprovalHash);
        assertEquals("original\n", Files.readString(Path.of(state.candidatePath).resolve("README.md")));
        engine.approve(id, state.pendingApprovalHash, true);
        assertEquals(RunStatus.COMPLETED, engine.advance(id).status);
        assertEquals("reviewed change\n", Files.readString(Path.of(state.candidatePath).resolve("README.md")));
        assertEquals("original\n", Files.readString(root.resolve("README.md")));
    }

    @Test void changedProposalRequiresFreshApprovalBeforeApplication() throws Exception {
        var state = proposal();
        engine.approve(state.id, state.pendingApprovalHash, true);
        Path proposal = root.resolve("evidence/" + state.id + "/apply-v1.txt");
        Files.writeString(proposal, PATCH.replace("reviewed change", "different change"));
        state = engine.advance(state.id);
        assertEquals(RunStatus.PAUSED, state.status);
        assertEquals("apply", state.pendingApprovalTask);
        assertNotEquals(state.approvals.get("apply"), state.pendingApprovalHash);
        assertEquals("original\n", Files.readString(Path.of(state.candidatePath).resolve("README.md")));
    }

    @Test void parallelPolicyFailureRemainsTerminalWhenAnotherBranchFails() throws Exception {
        for (boolean securityFirst : List.of(true, false)) {
            var policy = artifact("policy", List.of(), "../outside.txt");
            var transientFailure = artifact("transient", List.of(), "missing.txt");
            var tasks = new ArrayList<>(securityFirst ? List.of(policy, transientFailure) : List.of(transientFailure, policy));
            tasks.add(artifact("join", List.of("policy", "transient"), "output.txt"));
            var state = engine.advance(start(tasks).id);
            assertEquals(RunStatus.SAFE_STOPPED, state.status);
            assertEquals(TaskStatus.FAILED, state.tasks.get("policy"));
            assertEquals(TaskStatus.PENDING, state.tasks.get("join"));
            assertNotNull(state.finishedAt);
            assertTrue(store.has("PARALLEL_JOIN"));
        }
    }

    @Test void completedEvidenceTamperStopsBeforeClarificationCanAdvance() throws Exception {
        Files.createDirectories(root.resolve("scenario"));
        Files.writeString(root.resolve("scenario/output.txt"), "original artifact");
        var clarify = new TaskSpec("clarify", Stage.REQUIREMENTS, List.of("understand"), TaskKind.CLARIFY,
            "operator", "Choose the scope", null, List.of(), List.of(), true);
        var state = engine.advance(start(List.of(artifact("understand", List.of(), "output.txt"), clarify)).id);
        assertEquals("clarify", state.pendingClarificationTask);
        Files.writeString(root.resolve("evidence/" + state.id + "/understand-v1.txt"), "tampered");
        assertEquals(RunStatus.SAFE_STOPPED, engine.advance(state.id).status);
        assertTrue(store.has("POLICY_SAFE_STOP"));
    }

    @Test void interruptedArtifactIsRecoveredWithoutRegenerationAndLeaseBlocksConcurrentAdvance() throws Exception {
        var state = start(List.of(artifact("generate", List.of(), "missing.txt")));
        state.tasks.put("generate", TaskStatus.RUNNING);
        store.record(state, "TEST_INTERRUPTION", "persisted task start");
        Path output = root.resolve("evidence/" + state.id + "/generate-v1.txt");
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

    private RunState proposal() throws Exception {
        Files.createDirectories(root.resolve("scenario"));
        Files.writeString(root.resolve("scenario/change.patch"), PATCH);
        var patch = new TaskSpec("apply", Stage.IMPLEMENTATION, List.of(), TaskKind.PATCH, "implementer", "Change README",
            "change.patch", List.of(), List.of("README.md"), true);
        var state = engine.advance(start(List.of(patch)).id);
        assertEquals(RunStatus.PAUSED, state.status);
        assertNotNull(state.pendingApprovalHash);
        return state;
    }

    private TaskSpec artifact(String id, List<String> dependencies, String fixture) {
        return new TaskSpec(id, Stage.REQUIREMENTS, dependencies, TaskKind.ARTIFACT, "requirements", "Inspect", fixture, List.of(), List.of(), false);
    }

    private RunState start(List<TaskSpec> tasks) throws Exception {
        Path spec = root.resolve("scenario/scenario.json");
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, Json.MAPPER.writeValueAsString(new ScenarioSpec("workflow-test", "Test governance", "test-baseline", tasks)));
        return engine.start(spec, "fixture");
    }

    private void git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        GitWorkspace.command(root, command, Duration.ofSeconds(5));
    }

    /** Copies on read/write to model persistence, rather than sharing a mutable RunState with the engine. */
    private static final class MemoryStore implements RunStore {
        private final Map<String, String> states = new HashMap<>();
        private final Set<String> leases = new HashSet<>();
        private final List<String> events = new ArrayList<>();
        @Override public synchronized RunState load(String id) throws Exception { return Json.MAPPER.readValue(states.get(id), RunState.class); }
        @Override public synchronized void record(RunState state, String type, String detail) throws Exception {
            states.put(state.id, Json.MAPPER.writeValueAsString(state));
            events.add(type + ":" + detail);
        }
        @Override public synchronized boolean auditValid(String id) { return states.containsKey(id); }
        @Override public synchronized AutoCloseable lease(String id) {
            if (!leases.add(id)) throw new IllegalStateException("Run is already being advanced");
            return () -> { synchronized (this) { leases.remove(id); } };
        }
        boolean has(String event) { return events.stream().anyMatch(value -> value.startsWith(event + ":")); }
    }
}
