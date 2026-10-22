package dev.softwarefactory.run;

import dev.softwarefactory.audit.AuditEvent;
import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.candidate.DraftWorkspace;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.platform.GenerationUnavailableException;
import dev.softwarefactory.platform.WorkflowConflictException;
import dev.softwarefactory.scenario.ScenarioFiles;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.nio.file.Path;

/** Narrow recovery of legacy test-tag safe stops; no approval, application or model call occurs here. */
public final class DraftRecovery {
    private static final String TAG_FAILURE =
            "Generated tests cannot opt out of validation with @Tag(\"integration\"): ";
    private final RunCore core;
    private final GitWorkspace workspace;
    private final String operator;

    DraftRecovery(RunCore core, GitWorkspace workspace, String operator) {
        this.core = core;
        this.workspace = workspace;
        this.operator = operator;
    }

    /** Display eligibility only. The transition rechecks inputs, evidence, scope and checkpoint integrity. */
    public static String eligibleTask(RunState state, AuditEvent latest) {
        if (state.status != RunStatus.SAFE_STOPPED
                || latest == null
                || !EventTypes.POLICY_SAFE_STOP.equals(latest.type())) return null;
        return state.drafting.keySet().stream()
                .filter(task -> state.tasks.get(task) == TaskStatus.FAILED)
                .filter(task -> latest.detail().startsWith(task + ":" + TAG_FAILURE))
                .findFirst()
                .orElse(null);
    }

    RunState recover(String id) throws IOException, InterruptedException {
        RunState state = core.runs().load(id);
        var latest = core.runs().latestEvent(id).orElse(null);
        String taskId = eligibleTask(state, latest);
        if (taskId == null)
            throw new WorkflowConflictException("This stop is not a recoverable draft test-tag failure");
        TaskSpec task = requireUnreviewedPatch(state, taskId);
        if (!core.integrity().verify(state)) throw new WorkflowConflictException("Evidence integrity failed");
        verifyDraft(state, task);
        String reason = latest.detail() + ". Remove the integration tag; keep every test and assertion enabled.";
        core.evidence().recordDiagnostic(state, taskId, new GenerationUnavailableException(reason), reason);
        state.status = RunStatus.PAUSED;
        state.finishedAt = null;
        state.tasks.put(taskId, TaskStatus.PENDING);
        state.attempts.remove(taskId);
        core.runs().record(state, "DRAFT_RECOVERED", taskId + ":legacy test-tag stop; operator=" + operator);
        return state;
    }

    private TaskSpec requireUnreviewedPatch(RunState state, String taskId) throws IOException {
        if (state.pendingApprovalTask != null
                || state.patchDrafts.containsKey(taskId)
                || state.artifactHashes.containsKey(taskId)
                || state.approvals.containsKey(taskId))
            throw new WorkflowConflictException("Only an unreviewed draft can be recovered");
        var document = ScenarioFiles.read(Path.of(state.specPath));
        if (!Hashes.same(Hashes.sha256(document.text()), state.specHash)
                || !Hashes.same(Hashes.sha256(document.spec().requirement()), state.requirementHash))
            throw new WorkflowConflictException("Scenario changed; draft recovery requires original inputs");
        return document.spec().tasks().stream()
                .filter(task -> task.id().equals(taskId) && task.kind() == TaskKind.PATCH)
                .findFirst()
                .orElseThrow(() -> new WorkflowConflictException("Patch task not found"));
    }

    private void verifyDraft(RunState state, TaskSpec task) throws IOException, InterruptedException {
        var saved = state.drafting.get(task.id());
        Path parent = java.util.Objects.requireNonNull(
                        Path.of(state.candidatePath).toAbsolutePath().getParent(), "Candidate needs a parent directory")
                .resolve("drafts")
                .normalize();
        if (!parent.equals(
                        Path.of(saved.directory()).toAbsolutePath().normalize().getParent())
                || !Hashes.same(saved.inputHash(), PatchDrafting.inputHash(state, task.id(), workspace)))
            throw new WorkflowConflictException("Draft location or source inputs changed");
        var draft = new DraftWorkspace(saved, workspace, task.writeScope(), checkpoint -> {}, (path, diff) -> "");
        draft.verify();
        workspace.checkApply(Path.of(state.candidatePath), draft.diff(), task.writeScope());
    }
}
