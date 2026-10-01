package dev.softwarefactory.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.softwarefactory.audit.AuditTrail;
import dev.softwarefactory.audit.ChatLedger;
import dev.softwarefactory.audit.ControlDatabase;
import dev.softwarefactory.audit.RunJournal;
import dev.softwarefactory.audit.RunLeases;
import dev.softwarefactory.generation.ModelClients;
import dev.softwarefactory.operator.api.ControlRecords;
import dev.softwarefactory.operator.api.FactoryService;
import dev.softwarefactory.platform.FactorySettings;
import dev.softwarefactory.platform.WorkflowConflictException;
import dev.softwarefactory.testing.IsolatedFactoryEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Opt-in fault injection against fixture runs in a throwaway schema and a disposable clone. */
@Tag("integration")
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class RunEngineResilienceTest {
    private IsolatedFactoryEnvironment environment;
    private Path root;
    private DurableRunStore repository;
    private AuditTrail trail;
    private ControlRecords records;
    private FactorySettings settings;
    private ModelClients clients;
    private RunEngine engine;

    @BeforeEach
    void setup() throws Exception {
        environment =
                new IsolatedFactoryEnvironment("resilience_test").withSchema().withWorkspace();
        root = environment.workspace();
        var database = ControlDatabase.open(environment.url(), environment.user(), environment.password());
        trail = new AuditTrail(database, IsolatedFactoryEnvironment.AUDIT_KEY);
        repository = new DurableRunStore(
                new RunJournal(database, IsolatedFactoryEnvironment.AUDIT_KEY), trail, new RunLeases(database));
        records = new ControlRecords(repository, trail, new ChatLedger(database));
        settings = FactorySettings.from(Map.of());
        clients = new ModelClients(settings);
        engine = new RunEngine(repository, root, settings, clients);
    }

    @AfterEach
    void cleanup() throws Exception {
        clients.close();
        environment.close();
    }

    private RunState proposal() throws Exception {
        RunState state = engine.start(root.resolve("scenarios/greenfield/scenario.json"), "fixture");
        state = engine.advance(state.id);
        assertEquals("apply", state.pendingApprovalTask);
        return state;
    }

    @Test
    void alteredEvidenceSafeStopsBeforePendingApprovalCanAdvance() throws Exception {
        RunState state = proposal();
        Path artifact = root.resolve("evidence/" + state.id + "/understand-v1.txt");
        Files.writeString(artifact, Files.readString(artifact) + "\nINJECTED_TAMPER\n");
        assertEquals(RunStatus.SAFE_STOPPED, engine.advance(state.id).status);
        assertTrue(repository.auditValid(state.id));
    }

    @Test
    void missingEvidenceRemainsInspectableAndRejectable() throws Exception {
        RunState state = proposal();
        try (var service = new FactoryService(root, records, settings, clients)) {
            Files.delete(root.resolve("evidence/" + state.id + "/apply-v1.txt"));
            var review = (Map<?, ?>) service.detail(state.id).get("review");
            assertTrue(review.get("patch").toString().contains("Evidence unavailable"));
            service.reject(state.id, state.pendingApprovalHash);
            assertEquals(RunStatus.NOT_APPROVED, repository.load(state.id).status);
        }
    }

    @Test
    void tamperingCannotBeApprovedEvenWithThePreviouslyCorrectHash() throws Exception {
        RunState state = proposal();
        Files.writeString(root.resolve("evidence/" + state.id + "/understand-v1.txt"), "altered");
        assertThrows(IllegalStateException.class, () -> engine.approve(state.id, state.pendingApprovalHash, true));
        assertEquals(RunStatus.SAFE_STOPPED, repository.load(state.id).status);
        assertFalse(
                trail.timeline(state.id).stream().anyMatch(event -> event.type().equals("APPROVAL_GRANTED")));
    }

    @Test
    void concurrentAdvanceCannotAcquireTheSameRun() throws Exception {
        RunState state = proposal();
        try (var lease = repository.lease(state.id)) {
            assertThrows(WorkflowConflictException.class, () -> engine.advance(state.id));
        }
        assertEquals(state.pendingApprovalHash, repository.load(state.id).pendingApprovalHash);
        assertTrue(repository.auditValid(state.id));
    }

    @Test
    void recoveredPatchInvalidatesAndRerunsDownstreamValidation() throws Exception {
        RunState state = proposal();
        engine.approve(state.id, state.pendingApprovalHash, true);
        state = engine.advance(state.id);
        assertEquals("release", state.pendingApprovalTask);
        int validatedVersion = state.artifactVersions.get("validate");
        assertTrue(trail.timeline(state.id).stream().anyMatch(e -> e.type().equals("PARALLEL_JOIN")));
        // Reconstruct a persisted PATCH_STARTED snapshot with its completed output already on disk.
        state.pendingApprovalTask = null;
        state.pendingApprovalHash = null;
        state.status = RunStatus.RUNNING;
        state.tasks.put("apply", TaskStatus.RUNNING);
        state.artifactVersions.put("apply", state.artifactVersions.get("apply") - 1);
        state.artifactHashes.remove("apply");
        state.attempts.put("validate", 1);
        repository.record(
                state,
                "TEST_INTERRUPTION_INJECTED",
                "fixture-only patch checkpoint; downstream evidence must be refreshed");
        state = engine.advance(state.id);
        assertEquals("release", state.pendingApprovalTask);
        assertTrue(state.artifactVersions.get("validate") > validatedVersion);
        assertEquals(TaskStatus.DONE, state.tasks.get("apply"));
        assertFalse(state.attempts.containsKey("validate"), "Invalidated downstream tasks get a fresh retry budget");
        var events = trail.timeline(state.id);
        assertTrue(events.stream().anyMatch(e -> e.type().equals("RUN_RECOVERING")));
        assertTrue(events.stream()
                .anyMatch(e -> e.type().equals("RUN_RECOVERED") && e.detail().contains("candidateReset=true")));
        assertTrue(repository.auditValid(state.id));
    }
}
