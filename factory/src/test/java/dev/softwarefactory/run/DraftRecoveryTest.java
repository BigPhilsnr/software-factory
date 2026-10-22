package dev.softwarefactory.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.candidate.DraftCheckFailedException;
import dev.softwarefactory.candidate.DraftWorkspace;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.generation.AgentRuntime;
import dev.softwarefactory.generation.AgentRuntimes;
import dev.softwarefactory.governance.PolicyViolationException;
import dev.softwarefactory.platform.WorkflowConflictException;
import dev.softwarefactory.scenario.Stage;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DraftRecoveryTest {
    private static final String TEST_PATH = "shortener/src/test/java/FeatureTest.java";
    private static final String TAG = "@Tag(\"integration\")\n";
    private static final String SOURCE = "import org.junit.jupiter.api.Tag;\n" + TAG
            + "class FeatureTest { @org.junit.jupiter.api.Test void works() {} }\n";
    private static final String REASON =
            "apply:Generated tests cannot opt out of validation with @Tag(\"integration\"): " + TEST_PATH;

    @TempDir
    Path root;

    @Test
    void draftTestPolicyFeedbackCanBeRepairedBeforeApprovalWithoutSafeStopping() throws Exception {
        var fixture = new RunEngineFixture(root);
        var engine = fixture.engine(fixture.store, RunEngineFixture.LIVE_ENVIRONMENT, agents((prompt, draft) -> {
            draft.write(TEST_PATH, "ABSENT", SOURCE);
            var failure = assertThrows(DraftCheckFailedException.class, draft::check);
            assertTrue(failure.getMessage().contains("@Tag(\"integration\")"));
            draft.replace(TEST_PATH, TAG, "");
        }));
        var state = fixture.start(engine, RunMode.LIVE, tasks());
        state = engine.advance(state.id);
        assertEquals("apply", state.pendingApprovalTask);
        assertFalse(fixture.store.has(EventTypes.POLICY_SAFE_STOP));
        assertFalse(Files.exists(Path.of(state.candidatePath).resolve(TEST_PATH)));
        assertEquals(1, fixture.validator.requiredTestClasses.size());
    }

    @Test
    void legacyTagStopRecoversSavedWorkThenRequiresFreshValidationAndApproval() throws Exception {
        var fixture = new RunEngineFixture(root);
        var engine = fixture.engine(fixture.store, RunEngineFixture.LIVE_ENVIRONMENT, agents((prompt, draft) -> {
            assertTrue(prompt.contains("Draft test policy"));
            draft.replace(TEST_PATH, TAG, "");
        }));
        var stopped = legacyStop(fixture, engine);
        var recovered = engine.recoverDraft(stopped.id);
        assertEquals(RunStatus.PAUSED, recovered.status);
        assertEquals(TaskStatus.PENDING, recovered.tasks.get("apply"));
        assertEquals(stopped.drafting, recovered.drafting);
        assertNull(recovered.finishedAt);
        assertNull(recovered.pendingApprovalTask);
        assertEquals(0, recovered.modelCalls);
        assertTrue(fixture.store.has("DRAFT_RECOVERED"));
        assertTrue(
                Files.readString(fixture.evidence(recovered, "apply-error-v1")).contains("Remove the integration tag"));
        assertThrows(WorkflowConflictException.class, () -> engine.recoverDraft(stopped.id));
        recovered = engine.advance(stopped.id);
        assertEquals("apply", recovered.pendingApprovalTask);
        assertEquals(1, recovered.modelCalls);
        assertFalse(Files.exists(Path.of(recovered.candidatePath).resolve(TEST_PATH)));
    }

    @Test
    void refusesTamperedDraftAndChangedScenario() throws Exception {
        var fixture = new RunEngineFixture(root);
        var engine = fixture.engine(fixture.store, RunEngineFixture.LIVE_ENVIRONMENT, RunEngineFixture.unusedAgents());
        var state = legacyStop(fixture, engine);
        Path source = Path.of(state.drafting.get("apply").directory()).resolve(TEST_PATH);
        Files.writeString(source, "tampered");
        assertThrows(PolicyViolationException.class, () -> engine.recoverDraft(state.id));
        Files.writeString(source, SOURCE);
        fixture.writeScenario("Changed scope", tasks());
        assertThrows(WorkflowConflictException.class, () -> engine.recoverDraft(state.id));
        assertFalse(fixture.store.has("DRAFT_RECOVERED"));
    }

    @Test
    void refusesOtherPolicyStopsAndBrokenAudit() throws Exception {
        var fixture = new RunEngineFixture(root);
        var store = spy(fixture.store);
        var engine = fixture.engine(store, RunEngineFixture.LIVE_ENVIRONMENT, RunEngineFixture.unusedAgents());
        var state = legacyStop(fixture, engine);
        fixture.store.record(state, EventTypes.POLICY_SAFE_STOP, "apply:Path escaped workspace");
        assertNull(DraftRecovery.eligibleTask(
                state, fixture.store.latestEvent(state.id).orElseThrow()));
        assertThrows(WorkflowConflictException.class, () -> engine.recoverDraft(state.id));
        fixture.store.record(state, EventTypes.POLICY_SAFE_STOP, REASON);
        doReturn(false).when(store).auditValid(state.id);
        assertThrows(WorkflowConflictException.class, () -> engine.recoverDraft(state.id));
        assertFalse(fixture.store.has("DRAFT_RECOVERED"));
    }

    private RunState legacyStop(RunEngineFixture fixture, RunEngine engine) throws Exception {
        var state = fixture.start(engine, RunMode.LIVE, tasks());
        var git = new GitWorkspace(root);
        Path directory = Path.of(state.candidatePath).getParent().resolve("drafts/legacy");
        var saved = DraftWorkspace.create(
                Path.of(state.candidatePath), directory, PatchDrafting.inputHash(state, "apply", git));
        var draft = new DraftWorkspace(
                saved, git, List.of("shortener/src/test"), cp -> state.drafting.put("apply", cp), (p, d) -> "");
        draft.write(TEST_PATH, "ABSENT", SOURCE);
        state.tasks.put("apply", TaskStatus.FAILED);
        state.status = RunStatus.SAFE_STOPPED;
        state.finishedAt = RunEngineFixture.NOW;
        fixture.store.record(state, EventTypes.POLICY_SAFE_STOP, REASON);
        return state;
    }

    private static List<TaskSpec> tasks() {
        return List.of(
                new TaskSpec(
                        "apply",
                        Stage.IMPLEMENTATION,
                        List.of(),
                        TaskKind.PATCH,
                        "implementer",
                        "Add tests",
                        null,
                        List.of(),
                        List.of("shortener/src/test"),
                        true),
                RunEngineFixture.validate("validate", List.of("apply")));
    }

    @FunctionalInterface
    interface Edit {
        void run(String prompt, DraftWorkspace draft) throws IOException, InterruptedException;
    }

    private static AgentRuntimes agents(Edit edit) {
        return (checkout, beforeRequest, audit) -> new AgentRuntime() {
            public String generate(String role, String prompt) {
                throw new AssertionError("Draft expected");
            }

            public void draft(String role, String prompt, DraftWorkspace draft)
                    throws IOException, InterruptedException {
                beforeRequest.reserve();
                edit.run(prompt, draft);
            }
        };
    }
}
