package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.governance.PatchHunks;
import dev.softwarefactory.platform.WorkflowConflictException;
import dev.softwarefactory.scenario.ScenarioFiles;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import dev.softwarefactory.validation.GeneratedTestPolicy;
import java.io.IOException;
import java.nio.file.Path;

/** Recovers a historical count-only failure into fresh review, without generation or application. */
final class RecoverPatch {
    private final RunCore core;
    private final GitWorkspace workspace;

    RecoverPatch(RunCore core, GitWorkspace workspace) {
        this.core = core;
        this.workspace = workspace;
    }

    RunState recover(String id, String taskId, int version, String proposalHash)
            throws IOException, InterruptedException {
        RunState state = core.runs().load(id);
        TaskSpec task = requireEligible(state, taskId);
        if (!core.integrity().verify(state)) throw new WorkflowConflictException("Evidence integrity failed");
        String original = core.evidence().readProposal(state, taskId, version);
        if (!Hashes.same(proposalHash, Hashes.sha256(original)))
            throw new WorkflowConflictException("Saved proposal differs from the requested hash");
        String patch = PatchHunks.recount(original);
        if (patch.equals(original)) throw new WorkflowConflictException("Proposal has no incorrect hunk counts");
        GeneratedTestPolicy.check(patch);
        workspace.checkApply(Path.of(state.candidatePath), patch, task.writeScope());
        state.finishedAt = null;
        core.runs().record(state, "PATCH_COUNTS_RECOVERED", taskId + ":proposal-v" + version + ":" + proposalHash);
        new ApprovalGate(core.runs(), core.evidence()).pause(state, task, ApprovalGate.patchHash(state, patch), patch);
        return state;
    }

    private TaskSpec requireEligible(RunState state, String taskId) throws IOException {
        if (state.status != RunStatus.FAILED
                || state.tasks.get(taskId) != TaskStatus.FAILED
                || state.pendingApprovalTask != null
                || state.patchDrafts.containsKey(taskId))
            throw new WorkflowConflictException("Only an unreviewed failed patch can be recovered");
        requireCountFailure(state, taskId);
        return verifiedScenario(state).spec().tasks().stream()
                .filter(task -> task.id().equals(taskId) && task.kind() == TaskKind.PATCH)
                .findFirst()
                .orElseThrow(() -> new WorkflowConflictException("Patch task not found"));
    }

    private void requireCountFailure(RunState state, String taskId) throws IOException {
        var event = core.runs().latestEvent(state.id).orElseThrow();
        String diagnostic = core.evidence().latestDiagnostic(state, taskId);
        if (!EventTypes.TASK_FAILED.equals(event.type())
                || !event.detail().startsWith(taskId + ":")
                || diagnostic == null
                || !diagnostic.matches(
                        "java.lang.IllegalArgumentException: Hunk header declares -\\d+,\\+\\d+ lines but the body has -\\d+,\\+\\d+"))
            throw new WorkflowConflictException("The recorded failure is not a hunk-count mismatch");
    }

    private static ScenarioFiles.Document verifiedScenario(RunState state) throws IOException {
        var document = ScenarioFiles.read(Path.of(state.specPath));
        if (!Hashes.same(Hashes.sha256(document.text()), state.specHash)
                || !Hashes.same(Hashes.sha256(document.spec().requirement()), state.requirementHash))
            throw new WorkflowConflictException("Scenario changed; patch recovery requires the original inputs");
        return document;
    }
}
