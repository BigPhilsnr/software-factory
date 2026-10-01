package dev.softwarefactory.workflow;

import dev.softwarefactory.persistence.ControlRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in fault injection against isolated fixture runs and the local control database. */
@Tag("integration")
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class RunEngineResilienceTest {
    private Path root;
    private ControlRepository repository;
    private RunEngine engine;

    @BeforeEach void setup() throws Exception {
        root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName().toString().equals("factory")) root = root.getParent();
        repository = new ControlRepository(System.getenv().getOrDefault("CONTROL_DB_URL", "jdbc:postgresql://localhost:5434/control"), System.getenv().getOrDefault("CONTROL_DB_USER", "control"), System.getenv().getOrDefault("CONTROL_DB_PASSWORD", "control"));
        repository.initialize();
        engine = new RunEngine(repository, root);
    }

    private RunState proposal() throws Exception {
        RunState state = engine.start(root.resolve("scenarios/greenfield/scenario.json"), "fixture");
        state = engine.advance(state.id);
        assertEquals("apply", state.pendingApprovalTask);
        return state;
    }

    @Test void alteredEvidenceSafeStopsBeforePendingApprovalCanAdvance() throws Exception {
        RunState state = proposal();
        Path artifact = root.resolve("evidence/" + state.id + "/understand-v1.txt");
        String original = Files.readString(artifact);
        try {
            Files.writeString(artifact, original + "\nINJECTED_TAMPER\n");
            assertEquals(RunStatus.SAFE_STOPPED, engine.advance(state.id).status);
            assertTrue(repository.auditValid(state.id));
        } finally { Files.writeString(artifact, original); }
    }

    @Test void missingEvidenceRemainsInspectableAndRejectable() throws Exception {
        RunState state = proposal();
        Path artifact = root.resolve("evidence/" + state.id + "/apply-v1.txt");
        String original = Files.readString(artifact);
        try (var service = new dev.softwarefactory.operator.web.FactoryService(root, repository)) {
            Files.delete(artifact);
            var review = (java.util.Map<?, ?>) service.detail(state.id).get("review");
            assertTrue(review.get("patch").toString().contains("Evidence unavailable"));
            service.reject(state.id, state.pendingApprovalHash);
            assertEquals(RunStatus.NOT_APPROVED, repository.load(state.id).status);
        } finally { Files.writeString(artifact, original); }
    }

    @Test void tamperingCannotBeApprovedEvenWithThePreviouslyCorrectHash() throws Exception {
        RunState state = proposal();
        Path artifact = root.resolve("evidence/" + state.id + "/understand-v1.txt");
        String original = Files.readString(artifact);
        try {
            Files.writeString(artifact, "altered");
            assertThrows(IllegalStateException.class, () -> engine.approve(state.id, state.pendingApprovalHash, true));
            assertEquals(RunStatus.SAFE_STOPPED, repository.load(state.id).status);
            assertFalse(repository.timeline(state.id).stream().anyMatch(event -> event.type().equals("APPROVAL_GRANTED")));
        } finally { Files.writeString(artifact, original); }
    }

    @Test void concurrentAdvanceCannotAcquireTheSameRun() throws Exception {
        RunState state = proposal();
        try (var lease = repository.lease(state.id)) {
            assertThrows(IllegalStateException.class, () -> engine.advance(state.id));
        }
        assertEquals(state.pendingApprovalHash, repository.load(state.id).pendingApprovalHash);
        assertTrue(repository.auditValid(state.id));
    }

    @Test void recoveredPatchInvalidatesAndRerunsDownstreamValidation() throws Exception {
        RunState state = proposal();
        engine.approve(state.id, state.pendingApprovalHash, true);
        state = engine.advance(state.id);
        assertEquals("release", state.pendingApprovalTask);
        int validatedVersion = state.artifactVersions.get("validate");
        assertTrue(repository.timeline(state.id).stream().anyMatch(e -> e.type().equals("PARALLEL_JOIN")));
        // Reconstruct a persisted PATCH_STARTED snapshot with its completed output already on disk.
        state.pendingApprovalTask = null;
        state.pendingApprovalHash = null;
        state.status = RunStatus.RUNNING;
        state.tasks.put("apply", TaskStatus.RUNNING);
        state.artifactVersions.put("apply", state.artifactVersions.get("apply") - 1);
        state.artifactHashes.remove("apply");
        repository.record(state, "TEST_INTERRUPTION_INJECTED", "fixture-only patch checkpoint; downstream evidence must be refreshed");
        state = engine.advance(state.id);
        assertEquals("release", state.pendingApprovalTask);
        assertTrue(state.artifactVersions.get("validate") > validatedVersion);
        assertEquals(TaskStatus.DONE, state.tasks.get("apply"));
        assertTrue(repository.timeline(state.id).stream().anyMatch(e -> e.type().equals("RUN_RECOVERED") && e.detail().contains("candidateReset=true")));
        assertTrue(repository.auditValid(state.id));
        // Test-only rejection leaves no pending synthetic approval for the operator.
        engine.approve(state.id, state.pendingApprovalHash, false);
    }
}
