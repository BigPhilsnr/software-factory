package dev.softwarefactory.run;

import static dev.softwarefactory.run.RunEngineFixture.PATCH;
import static dev.softwarefactory.run.RunEngineFixture.artifact;
import static dev.softwarefactory.run.RunEngineFixture.clarify;
import static dev.softwarefactory.run.RunEngineFixture.patch;
import static dev.softwarefactory.run.RunEngineFixture.release;
import static dev.softwarefactory.run.RunEngineFixture.validate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.softwarefactory.platform.WorkflowConflictException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The operator's decisions: approve, reject, clarify and revise, and what each refuses. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RunEngineApprovalTest {
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
        fixture.fixture("change.patch", PATCH);
        fixture.fixture("output.txt", "recorded artifact");
    }

    /** A run paused at the release gate: its patch is applied and validated. */
    private RunState awaitingRelease() throws Exception {
        RunState state = fixture.start(
                engine,
                RunMode.FIXTURE,
                List.of(
                        patch("apply", List.of(), "change.patch", false),
                        validate("validate", List.of("apply")),
                        release("release", List.of("validate"))));
        state = engine.advance(state.id);
        assertEquals("release", state.pendingApprovalTask);
        return state;
    }

    @Test
    void releaseIsApprovedAgainstTheExactValidatedCandidateDiff() throws Exception {
        RunState state = awaitingRelease();
        assertEquals(RunStatus.PAUSED, state.status);
        assertEquals(TaskStatus.PENDING, state.tasks.get("release"));
        String reviewed = Files.readString(fixture.evidence(state, "release-v1"));
        assertTrue(reviewed.contains("+reviewed change"), "The operator reviews the complete candidate diff");
        assertEquals(List.of("release:" + state.pendingApprovalHash), store.details("APPROVAL_REQUIRED"));

        state = engine.approve(state.id, state.pendingApprovalHash, true);
        assertNull(state.pendingApprovalTask);
        assertEquals(RunStatus.PAUSED, state.status, "Approval only records the decision; advancing applies it");
        state = engine.advance(state.id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertEquals(List.of("release"), store.details("RELEASE_APPROVED"));
        assertTrue(store.details("APPROVAL_GRANTED").getFirst().startsWith("release:"));
    }

    @Test
    void rejectingEndsTheRunAsNotApprovedAndNothingCanFollow() throws Exception {
        RunState state = awaitingRelease();
        String id = state.id;
        String hash = state.pendingApprovalHash;
        state = engine.approve(id, hash, false);
        assertEquals(RunStatus.NOT_APPROVED, state.status);
        assertEquals(RunEngineFixture.NOW, state.finishedAt);
        assertTrue(store.details("APPROVAL_REJECTED").getFirst().startsWith("release:"));
        int events = store.eventCount();
        assertEquals(RunStatus.NOT_APPROVED, engine.advance(id).status);
        assertThrows(WorkflowConflictException.class, () -> engine.approve(id, hash, true));
        assertThrows(WorkflowConflictException.class, () -> engine.revise(id, "apply", "Try again"));
        assertEquals(events, store.eventCount(), "A terminal run records nothing further");
    }

    @Test
    void approvalNeedsAPendingReviewAndTheExactReviewedHash() throws Exception {
        RunState created = fixture.start(engine, RunMode.FIXTURE, List.of(artifact("write", List.of(), "output.txt")));
        assertThrows(WorkflowConflictException.class, () -> engine.approve(created.id, "0".repeat(64), true));
        RunState state = awaitingRelease();
        assertThrows(WorkflowConflictException.class, () -> engine.approve(state.id, "0".repeat(64), true));
        assertThrows(WorkflowConflictException.class, () -> engine.approve(state.id, null, false));
        assertEquals(RunStatus.PAUSED, store.load(state.id).status);
        assertFalse(store.has("APPROVAL_GRANTED") || store.has("APPROVAL_REJECTED"));
    }

    @Test
    void aProposalAlteredAfterReviewSafeStopsInsteadOfBeingApproved() throws Exception {
        RunState state = engine.advance(
                fixture.start(engine, RunMode.FIXTURE, List.of(patch("apply", List.of(), "change.patch", true))).id);
        Files.writeString(fixture.evidence(state, "apply-v1"), PATCH.replace("reviewed change", "swapped change"));
        String id = state.id;
        String hash = state.pendingApprovalHash;
        var refusal = assertThrows(WorkflowConflictException.class, () -> engine.approve(id, hash, true));
        assertTrue(refusal.getMessage().contains("approval was not granted"));
        assertEquals(RunStatus.SAFE_STOPPED, store.load(id).status);
        assertEquals(List.of("Pending proposal integrity failed"), store.details("POLICY_SAFE_STOP"));
        assertFalse(store.has("APPROVAL_GRANTED"));
    }

    @Test
    void aCandidateChangedAfterValidationMustBeValidatedAgainBeforeRelease() throws Exception {
        RunState state = awaitingRelease();
        engine.approve(state.id, state.pendingApprovalHash, true);
        Files.writeString(Path.of(state.candidatePath).resolve("README.md"), "changed behind the validator's back\n");
        state = engine.advance(state.id);
        assertEquals(RunStatus.PAUSED, state.status);
        assertEquals(TaskStatus.PENDING, state.tasks.get("release"));
        assertEquals(List.of("release:candidate changed after validation"), store.details("REVALIDATION_REQUIRED"));
        assertFalse(store.has("RELEASE_APPROVED"));
    }

    @Test
    void aReleaseApprovalDoesNotSurviveANewCandidateDiff() throws Exception {
        RunState state = awaitingRelease();
        String firstHash = state.pendingApprovalHash;
        Files.writeString(Path.of(state.candidatePath).resolve("README.md"), "changed after the review started\n");
        String id = state.id;
        assertThrows(WorkflowConflictException.class, () -> engine.approve(id, firstHash, true));
        assertEquals(RunStatus.SAFE_STOPPED, store.load(id).status, "The reviewed diff no longer is the candidate");
    }

    @Test
    void aClarificationAnswerBecomesEvidenceAndLetsTheRunContinue() throws Exception {
        RunState state = fixture.start(
                engine,
                RunMode.FIXTURE,
                List.of(
                        artifact("understand", List.of(), "output.txt"),
                        clarify("clarify", List.of("understand")),
                        artifact("plan", List.of("clarify"), "output.txt")));
        state = engine.advance(state.id);
        assertEquals(RunStatus.PAUSED, state.status);
        assertEquals("clarify", state.pendingClarificationTask);
        assertEquals(List.of("clarify:Choose the scope"), store.details("CLARIFICATION_REQUIRED"));
        String id = state.id;
        assertThrows(WorkflowConflictException.class, () -> engine.clarify(id, "   "));

        state = engine.clarify(id, "Single instance only.");
        assertNull(state.pendingClarificationTask);
        assertEquals(TaskStatus.DONE, state.tasks.get("clarify"));
        assertEquals("Single instance only.", Files.readString(fixture.evidence(state, "clarify-v1")));
        assertThrows(WorkflowConflictException.class, () -> engine.clarify(id, "A second answer"));
        assertEquals(RunStatus.COMPLETED, engine.advance(id).status);
    }

    @Test
    void aChangedScenarioPausesUntilTheOperatorExplicitlyReplans() throws Exception {
        var tasks = List.of(artifact("understand", List.of(), "output.txt"), clarify("clarify", List.of("understand")));
        RunState state = engine.advance(fixture.start(engine, RunMode.FIXTURE, tasks).id);
        String originalRequirementHash = state.requirementHash;
        state = engine.clarify(state.id, "Proceed.");
        fixture.writeScenario("A different requirement", List.of(artifact("understand", List.of(), "output.txt")));

        state = engine.advance(state.id);
        assertEquals(RunStatus.PAUSED, state.status);
        assertTrue(store.has("REPLAN_REQUIRED"));
        assertEquals(TaskStatus.DONE, state.tasks.get("understand"), "Nothing is invalidated implicitly");

        state = engine.revise(state.id, "understand", null);
        assertNotEquals(originalRequirementHash, state.requirementHash);
        assertEquals(List.of("understand"), List.copyOf(state.tasks.keySet()), "Removed tasks leave the run");
        assertEquals(TaskStatus.PENDING, state.tasks.get("understand"));
        assertFalse(store.has("REVIEW_FEEDBACK_RECORDED"), "Revising without feedback records none");
        state = engine.advance(state.id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertEquals(2, state.artifactVersions.get("understand"), "Regenerated work is a new evidence version");
    }

    @Test
    void revisionRefusesUnusableFeedbackAndUnknownTasks() throws Exception {
        RunState state = fixture.start(engine, RunMode.FIXTURE, List.of(artifact("write", List.of(), "output.txt")));
        String id = state.id;
        assertThrows(IllegalArgumentException.class, () -> engine.revise(id, "write", " "));
        assertThrows(IllegalArgumentException.class, () -> engine.revise(id, "write", "x".repeat(8_001)));
        assertThrows(IllegalArgumentException.class, () -> engine.revise(id, "missing", "Useful feedback"));
        assertFalse(store.has("PARTIAL_REPLAN"));
    }

    @Test
    void revisionPreservesUnaffectedWorkAndWithdrawsPendingReviews() throws Exception {
        RunState state = fixture.start(
                engine,
                RunMode.FIXTURE,
                List.of(
                        artifact("understand", List.of(), "output.txt"),
                        patch("apply", List.of("understand"), "change.patch", true)));
        state = engine.advance(state.id);
        assertEquals("apply", state.pendingApprovalTask);
        state = engine.revise(state.id, "apply", "Smaller change please.");
        assertNull(state.pendingApprovalTask);
        assertNull(state.pendingApprovalHash);
        assertEquals(TaskStatus.DONE, state.tasks.get("understand"));
        assertEquals(TaskStatus.PENDING, state.tasks.get("apply"));
        assertTrue(state.patchDrafts.isEmpty(), "The reviewed draft is not reused after a revision");
        assertTrue(store.details("PARTIAL_REPLAN").getFirst().contains("preserved=[understand]"));
    }
}
