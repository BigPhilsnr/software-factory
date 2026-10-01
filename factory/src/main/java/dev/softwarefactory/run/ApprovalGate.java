package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.util.TreeMap;

/**
 * The human gate. An approval is bound to a hash of exactly what was reviewed together with everything
 * it was derived from, so a changed proposal, baseline, requirement or upstream artifact needs a new decision.
 */
final class ApprovalGate {
    private final RunStore runs;
    private final RunEvidence evidence;

    ApprovalGate(RunStore runs, RunEvidence evidence) {
        this.runs = runs;
        this.evidence = evidence;
    }

    static String patchHash(RunState state, String patch) {
        return Hashes.sha256(patch
                + state.baselineCommit
                + state.requirementHash
                + state.specHash
                + new TreeMap<>(state.artifactHashes));
    }

    static String releaseHash(RunState state, String candidateDiff) {
        return Hashes.sha256(candidateDiff + new TreeMap<>(state.artifactHashes));
    }

    /** True when the operator approved exactly this hash for the task. */
    static boolean approved(RunState state, TaskSpec task, String hash) {
        return Hashes.same(hash, state.approvals.get(task.id()));
    }

    /** Snapshots exactly what is being reviewed (proposed patch or candidate diff) as new evidence, then waits. */
    void pause(RunState state, TaskSpec task, String hash, String reviewed) throws IOException {
        int version = evidence.snapshot(state, task.id(), reviewed);
        if (task.kind() == TaskKind.PATCH) state.patchDrafts.put(task.id(), version);
        state.pendingApprovalTask = task.id();
        state.pendingApprovalHash = hash;
        state.tasks.put(task.id(), TaskStatus.PENDING);
        state.status = RunStatus.PAUSED;
        runs.record(state, EventTypes.APPROVAL_REQUIRED, task.id() + ":" + hash);
    }
}
