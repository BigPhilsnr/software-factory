package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.governance.PatchPolicy;
import dev.softwarefactory.governance.PatchScope;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.TaskSpec;
import dev.softwarefactory.validation.GeneratedTestPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * PATCH: an agent proposes a unified diff. It is kept as evidence, checked against the task's write scope
 * and the test policy, held for exact-hash approval when policy requires it, and only then applied to the
 * candidate.
 */
final class PatchTask implements TaskExecutor {
    private final RunStore runs;
    private final TaskGeneration generation;
    private final RunEvidence evidence;
    private final GitWorkspace workspace;
    private final ApprovalGate gate;
    private final FailureHandling failures;

    PatchTask(
            RunStore runs,
            TaskGeneration generation,
            RunEvidence evidence,
            GitWorkspace workspace,
            ApprovalGate gate,
            FailureHandling failures) {
        this.runs = runs;
        this.generation = generation;
        this.evidence = evidence;
        this.workspace = workspace;
        this.gate = gate;
        this.failures = failures;
    }

    @Override
    public boolean execute(RunState state, TaskSpec task, ScenarioSpec spec) throws IOException, InterruptedException {
        boolean drafted = state.patchDrafts.containsKey(task.id());
        Optional<String> proposed = failures.produce(state, task, () -> draft(state, task, spec));
        if (proposed.isEmpty()) return false;
        String patch = proposed.get();
        if (!drafted) evidence.recordProposal(state, task.id(), patch);
        if (!failures.attempt(state, task, () -> requireWithinAuthority(state, task, patch))) return false;
        String hash = ApprovalGate.patchHash(state, patch);
        if (PatchPolicy.requiresApproval(task.requiresApproval(), patch) && !ApprovalGate.approved(state, task, hash)) {
            gate.pause(state, task, hash, patch);
            return false;
        }
        return failures.attempt(state, task, () -> apply(state, task, patch, hash));
    }

    /** The draft an operator already reviewed, or a newly generated patch. */
    private String draft(RunState state, TaskSpec task, ScenarioSpec spec) throws IOException {
        Integer version = state.patchDrafts.get(task.id());
        if (version != null) return evidence.readOutput(state, task.id(), version);
        state.tasks.put(task.id(), TaskStatus.RUNNING);
        runs.record(state, EventTypes.TASK_STARTED, task.id());
        return generation.generate(state, task, generation.prepare(state, task, spec));
    }

    private void requireWithinAuthority(RunState state, TaskSpec task, String patch)
            throws IOException, InterruptedException {
        PatchScope.changedPaths(patch, task.writeScope());
        GeneratedTestPolicy.check(patch);
        workspace.checkApply(Path.of(state.candidatePath), patch, task.writeScope());
    }

    private void apply(RunState state, TaskSpec task, String patch, String hash)
            throws IOException, InterruptedException {
        saveDraft(state, task, patch);
        state.tasks.put(task.id(), TaskStatus.RUNNING);
        runs.record(state, EventTypes.PATCH_STARTED, task.id() + ":" + hash);
        workspace.apply(Path.of(state.candidatePath), patch, task.writeScope());
        state.validatedCandidateHash = null;
        evidence.complete(state, task, patch);
    }

    private void saveDraft(RunState state, TaskSpec task, String patch) throws IOException {
        if (state.patchDrafts.containsKey(task.id())) return;
        int version = evidence.snapshot(state, task.id(), patch);
        state.patchDrafts.put(task.id(), version);
        runs.record(state, EventTypes.PATCH_DRAFTED, task.id() + ":v" + version);
    }
}
