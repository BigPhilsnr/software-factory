package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.nio.file.Path;

/**
 * RELEASE: the final human gate. The candidate must still be exactly what validation proved, and the
 * operator must approve the hash of its complete diff.
 */
final class ReleaseTask implements TaskExecutor {
    private final RunStore runs;
    private final GitWorkspace workspace;
    private final EvidenceIntegrity integrity;
    private final ApprovalGate gate;

    ReleaseTask(RunStore runs, GitWorkspace workspace, EvidenceIntegrity integrity, ApprovalGate gate) {
        this.runs = runs;
        this.workspace = workspace;
        this.integrity = integrity;
        this.gate = gate;
    }

    @Override
    public boolean execute(RunState state, TaskSpec task, ScenarioSpec spec) throws IOException, InterruptedException {
        if (!integrity.verify(state)) return false;
        String diff = workspace.diff(Path.of(state.candidatePath), state.baselineCommit);
        if (!Hashes.same(Hashes.sha256(diff), state.validatedCandidateHash)) {
            state.status = RunStatus.PAUSED;
            runs.record(state, EventTypes.REVALIDATION_REQUIRED, task.id() + ":candidate changed after validation");
            return false;
        }
        String candidateHash = ApprovalGate.releaseHash(state, diff);
        if (!ApprovalGate.approved(state, task, candidateHash)) {
            gate.pause(state, task, candidateHash, diff);
            return false;
        }
        state.tasks.put(task.id(), TaskStatus.DONE);
        runs.record(state, EventTypes.RELEASE_APPROVED, task.id());
        return true;
    }
}
