package dev.softwarefactory.run;

import static org.junit.jupiter.api.Assertions.*;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.governance.PatchHunks;
import dev.softwarefactory.platform.WorkflowConflictException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchRecoveryTest {
    private static final String MALFORMED = RunEngineFixture.PATCH.replace("@@ -1 +1 @@", "@@ -1,6 +1,7 @@");
    private static final String DIAGNOSTIC =
            "java.lang.IllegalArgumentException: Hunk header declares -6,+7 lines but the body has -1,+1";

    @TempDir
    Path root;

    @Test
    void generationRecountsBeforeReviewAndPreservesOriginalEvidence() throws Exception {
        var fixture = new RunEngineFixture(root);
        fixture.fixture("change.diff", MALFORMED);
        var engine = fixture.engine();
        var state = fixture.start(
                engine, RunMode.FIXTURE, List.of(RunEngineFixture.patch("change", List.of(), "change.diff", true)));
        state = engine.advance(state.id);
        assertEquals(RunStatus.PAUSED, state.status);
        assertEquals("change", state.pendingApprovalTask);
        assertEquals(MALFORMED, Files.readString(fixture.evidence(state, "change-proposal-v1")));
        assertEquals(PatchHunks.recount(MALFORMED), Files.readString(fixture.evidence(state, "change-v1")));
        assertEquals("original\n", fixture.candidateFile(state, "README.md"));
        assertTrue(state.attempts.isEmpty());
        engine.approve(state.id, state.pendingApprovalHash, true);
        state = engine.advance(state.id);
        assertEquals(TaskStatus.DONE, state.tasks.get("change"));
        assertEquals("reviewed change\n", fixture.candidateFile(state, "README.md"));
    }

    @Test
    void legacyFailureReturnsToExactReviewWithoutGenerationOrApplying() throws Exception {
        var fixture = new RunEngineFixture(root);
        var engine = fixture.engine();
        var state = failed(fixture, engine, DIAGNOSTIC);
        assertThrows(
                WorkflowConflictException.class,
                () -> engine.recoverPatch(state.id, "change", 1, Hashes.sha256("wrong")));
        var recovered = engine.recoverPatch(state.id, "change", 1, Hashes.sha256(MALFORMED));
        assertEquals(RunStatus.PAUSED, recovered.status);
        assertEquals("change", recovered.pendingApprovalTask);
        assertNull(recovered.finishedAt);
        assertEquals(state.modelCalls, recovered.modelCalls);
        assertEquals(state.attempts, recovered.attempts);
        assertEquals("original\n", fixture.candidateFile(recovered, "README.md"));
        assertEquals(MALFORMED, Files.readString(fixture.evidence(recovered, "change-proposal-v1")));
        assertTrue(fixture.store.has("PATCH_COUNTS_RECOVERED"));
        assertThrows(
                WorkflowConflictException.class,
                () -> engine.recoverPatch(state.id, "change", 1, Hashes.sha256(MALFORMED)));
        engine.approve(recovered.id, recovered.pendingApprovalHash, true);
        recovered = engine.advance(recovered.id);
        assertEquals(TaskStatus.DONE, recovered.tasks.get("change"));
    }

    @Test
    void refusesUnrelatedFailureAndPolicyStop() throws Exception {
        var fixture = new RunEngineFixture(root);
        var engine = fixture.engine();
        var state = failed(fixture, engine, "java.lang.IllegalArgumentException: unrelated failure");
        assertThrows(
                WorkflowConflictException.class,
                () -> engine.recoverPatch(state.id, "change", 1, Hashes.sha256(MALFORMED)));
        state.status = RunStatus.SAFE_STOPPED;
        fixture.store.record(state, EventTypes.POLICY_SAFE_STOP, "change:policy failure");
        assertThrows(
                WorkflowConflictException.class,
                () -> engine.recoverPatch(state.id, "change", 1, Hashes.sha256(MALFORMED)));
    }

    private RunState failed(RunEngineFixture fixture, RunEngine engine, String diagnostic) throws Exception {
        var state = fixture.start(
                engine, RunMode.FIXTURE, List.of(RunEngineFixture.patch("change", List.of(), "unused.diff", true)));
        Files.createDirectories(fixture.evidence(state, "change-proposal-v1").getParent());
        Files.writeString(fixture.evidence(state, "change-proposal-v1"), MALFORMED);
        Files.writeString(fixture.evidence(state, "change-error-v2"), diagnostic);
        state.attempts.put("change", 2);
        state.tasks.put("change", TaskStatus.FAILED);
        state.status = RunStatus.FAILED;
        state.finishedAt = RunEngineFixture.NOW;
        fixture.store.record(state, EventTypes.TASK_FAILED, "change:change-error-v2");
        return state;
    }
}
