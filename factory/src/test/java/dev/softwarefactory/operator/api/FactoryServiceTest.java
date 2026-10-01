package dev.softwarefactory.operator.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.softwarefactory.audit.AuditTrail;
import dev.softwarefactory.audit.ChatLedger;
import dev.softwarefactory.generation.ModelClients;
import dev.softwarefactory.platform.FactorySettings;
import dev.softwarefactory.platform.Json;
import dev.softwarefactory.platform.WorkflowConflictException;
import dev.softwarefactory.run.DurableRunStore;
import dev.softwarefactory.run.RunEngine;
import dev.softwarefactory.run.RunState;
import dev.softwarefactory.run.RunStatus;
import dev.softwarefactory.run.TaskStatus;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.Stage;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import dev.softwarefactory.validation.Preflight;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FactoryServiceTest {
    private static final String ID = "00000000-0000-0000-0000-0000000000bb";

    @TempDir
    Path root;

    private final DurableRunStore runs = mock(DurableRunStore.class);
    private final AuditTrail trail = mock(AuditTrail.class);
    private final RunEngine engine = mock(RunEngine.class);
    private final FactorySettings settings = FactorySettings.from(Map.of());
    private FactoryService service;
    private RunState state;

    @BeforeEach
    void setup() throws Exception {
        service = service(ConcurrentHashMap.newKeySet());
        var spec = new ScenarioSpec(
                "demo",
                "Add expiry",
                "url-v4",
                List.of(new TaskSpec(
                        "validate",
                        Stage.VALIDATION,
                        List.of(),
                        TaskKind.VALIDATE,
                        "validator",
                        "Test",
                        null,
                        List.of(),
                        List.of(),
                        false)));
        Path scenario = root.resolve("scenario.json");
        Files.writeString(scenario, Json.MAPPER.writeValueAsString(spec));
        state = new RunState(ID, "demo", "hash");
        state.specPath = scenario.toString();
        state.startedAt = Instant.parse("2026-02-01T08:00:00Z");
        state.tasks.put("validate", TaskStatus.PENDING);
        when(runs.load(ID)).thenReturn(state);
        when(engine.validatorStatus()).thenReturn(new Preflight(true, "ready", Instant.EPOCH));
    }

    @AfterEach
    void close() {
        service.close();
    }

    private FactoryService service(Set<String> active) {
        return new FactoryService(
                root,
                new ControlRecords(runs, trail, mock(ChatLedger.class)),
                settings,
                engine,
                (role, prompt) -> "chat answer",
                active);
    }

    @Test
    void fullCapacityRejectsBeforeTouchingDecisionPersistence() throws Exception {
        try (var full = service(Set.of("one", "two"))) {
            assertThrows(ServiceUnavailableException.class, () -> full.approve("third", "hash"));
            assertThrows(ServiceUnavailableException.class, () -> full.clarify("third", "answer"));
            assertThrows(ServiceUnavailableException.class, () -> full.revise("third", "task", "feedback"));
            assertThrows(ServiceUnavailableException.class, () -> full.reject("third", "a".repeat(64)));
            assertTrue(full.busy("one"));
        }
        verify(engine, never()).approve(any(), any(), eq(true));
        verify(engine, never()).clarify(any(), any());
    }

    @Test
    void operatorInputIsValidatedBeforeAnyDecisionIsRecorded() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.reject(ID, null));
        assertThrows(IllegalArgumentException.class, () -> service.reject(ID, "not-a-hash"));
        assertThrows(IllegalArgumentException.class, () -> service.clarify(ID, " "));
        assertThrows(IllegalArgumentException.class, () -> service.clarify(ID, "x".repeat(8001)));
        assertThrows(IllegalArgumentException.class, () -> service.revise(ID, "task", null));
        assertThrows(IllegalArgumentException.class, () -> service.scenario("unknown", "fixture"));
        assertThrows(IllegalArgumentException.class, () -> service.scenario(null, "fixture"));
        assertThrows(IllegalArgumentException.class, () -> service.feature(" "));
        verify(engine, never()).approve(any(), any(), eq(false));
        verify(engine, never()).start(any(), any());
    }

    @Test
    void advancingRunsOnAWorkerAndDecisionsAdvanceTheRunAfterwards() throws Exception {
        service.advance(ID);
        awaitIdle();
        service.approve(ID, "a".repeat(64));
        verify(engine).approve(ID, "a".repeat(64), true);
        awaitIdle();
        service.clarify(ID, "One region");
        verify(engine).clarify(ID, "One region");
        awaitIdle();
        service.revise(ID, "validate", "Rework it");
        verify(engine).revise(ID, "validate", "Rework it");
        awaitIdle();
        verify(engine, times(4)).advance(ID);
        service.reject(ID, "b".repeat(64));
        verify(engine).approve(ID, "b".repeat(64), false);
    }

    @Test
    void aRunThatWaitsForTheOperatorOrHasEndedCannotBeAdvanced() throws Exception {
        state.pendingApprovalTask = "apply";
        assertThrows(WorkflowConflictException.class, () -> service.advance(ID));
        state.pendingApprovalTask = null;
        state.pendingClarificationTask = "clarify";
        assertThrows(WorkflowConflictException.class, () -> service.advance(ID));
        state.pendingClarificationTask = null;
        state.revisionRequiredTask = "validate";
        var revision = assertThrows(WorkflowConflictException.class, () -> service.advance(ID));
        assertTrue(revision.getMessage().contains("request changes to an upstream patch"));
        state.revisionRequiredTask = null;
        state.status = RunStatus.COMPLETED;
        assertThrows(WorkflowConflictException.class, () -> service.advance(ID));
        verify(engine, never()).advance(any());
    }

    @Test
    void pendingValidationIsRefusedUpFrontWhileTheSandboxIsUnavailable() throws Exception {
        when(engine.validatorStatus()).thenReturn(new Preflight(false, "Docker is not available", Instant.EPOCH));
        var unavailable = assertThrows(ServiceUnavailableException.class, () -> service.advance(ID));
        assertEquals("Docker is not available", unavailable.getMessage());
        assertFalse(service.busy(ID));
        state.tasks.put("validate", TaskStatus.DONE);
        service.advance(ID);
        awaitIdle();
        verify(engine).advance(ID);
    }

    private void awaitIdle() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (service.busy(ID) && System.nanoTime() < deadline) Thread.onSpinWait();
        assertFalse(service.busy(ID), "The worker did not finish");
    }

    @Test
    void featureRequestsBecomeLiveRunsOfTheTrustedWorkflowAndScenariosAreNamed() throws Exception {
        when(engine.start(any(), any())).thenReturn(state);
        assertEquals(state, service.feature("Add link expiry"));
        try (var requests = Files.list(root.resolve(".runs/requests"))) {
            Path request = requests.findFirst().orElseThrow();
            verify(engine).start(request, "live");
            ScenarioSpec saved = Json.MAPPER.readValue(Files.readString(request), ScenarioSpec.class);
            assertEquals("Add link expiry", saved.requirement());
            assertEquals("feature-request", saved.id());
        }
        service.scenario("bugfix", "fixture");
        verify(engine).start(root.toAbsolutePath().normalize().resolve("scenarios/bugfix/scenario.json"), "fixture");
    }

    @Test
    void readSideAndCollaboratorsAreExposedToTheOtherOperatorSurfaces() throws Exception {
        when(runs.recentRuns()).thenReturn(List.of(state));
        when(trail.recentEvents(ID)).thenReturn(List.of());
        when(trail.timeline(ID)).thenReturn(List.of());
        when(engine.sweepOrphanedValidatorContainers(false)).thenReturn(2);
        assertEquals(List.of(state), service.runs());
        assertEquals(state, service.state(ID));
        assertEquals("Add expiry", service.detail(ID).get("requirement"));
        assertEquals("", service.detail(ID).get("error"));
        assertEquals(root.toAbsolutePath().normalize(), service.workspaceRoot());
        assertEquals("chat answer", service.chatRuntime().generate("project_chat", "hello"));
        assertTrue(service.validatorStatus().ready());
        assertEquals(2, service.sweepOrphanedValidatorContainers(false));
        assertThrows(NotFoundException.class, () -> service.artifact(ID, "plan-v1.txt"));
        assertTrue(service.metrics().containsKey("fixture"));
    }

    @Test
    void productionWiringBuildsItsOwnEngineAndSweepsContainersOnClose() {
        try (var clients = new ModelClients(settings);
                var wired = new FactoryService(root, new ControlRecords(runs, trail, null), settings, clients)) {
            assertFalse(wired.busy(ID));
            assertEquals(root.toAbsolutePath().normalize(), wired.workspaceRoot());
        }
    }
}
