package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.platform.LogText;
import dev.softwarefactory.platform.WorkflowConflictException;
import dev.softwarefactory.scenario.ScenarioFiles;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records the operator's decision on a pending review. Approval is granted only for the exact hash the
 * operator saw, and only while the evidence behind it is intact; rejection ends the run.
 */
final class ApproveStep {
    private static final Logger LOG = LoggerFactory.getLogger(ApproveStep.class);

    private final RunStore runs;
    private final RunEvidence evidence;
    private final EvidenceIntegrity integrity;
    private final GitWorkspace workspace;
    private final String operator;
    private final Clock clock;

    ApproveStep(RunCore core, GitWorkspace workspace, String operator) {
        this.runs = core.runs();
        this.evidence = core.evidence();
        this.integrity = core.integrity();
        this.workspace = workspace;
        this.operator = operator;
        this.clock = core.clock();
    }

    RunState decide(String id, String reviewedHash, boolean accepted) throws IOException, InterruptedException {
        RunState state = runs.load(id);
        if (state.status != RunStatus.PAUSED || state.pendingApprovalTask == null)
            throw new WorkflowConflictException("No pending approval");
        if (!Hashes.same(state.pendingApprovalHash, reviewedHash))
            throw new WorkflowConflictException("Reviewed hash differs from the pending approval");
        return accepted ? approve(state, reviewedHash) : reject(state);
    }

    private RunState reject(RunState state) throws IOException {
        state.status = RunStatus.NOT_APPROVED;
        state.finishedAt = clock.instant();
        runs.record(state, EventTypes.APPROVAL_REJECTED, state.pendingApprovalTask + ":" + operator);
        LOG.info("Run {} rejected by {}", LogText.singleLine(state.id), LogText.singleLine(operator));
        return state;
    }

    private RunState approve(RunState state, String reviewedHash) throws IOException, InterruptedException {
        if (!integrity.verify(state))
            throw new WorkflowConflictException("Evidence integrity failed; approval was not granted");
        if (!proposalIntact(state, reviewedHash)) {
            state.status = RunStatus.SAFE_STOPPED;
            state.finishedAt = clock.instant();
            runs.record(state, EventTypes.POLICY_SAFE_STOP, "Pending proposal integrity failed");
            throw new WorkflowConflictException("Pending proposal changed or is missing; approval was not granted");
        }
        String approvedTask = state.pendingApprovalTask;
        String approvedHash = state.pendingApprovalHash;
        state.approvals.put(approvedTask, approvedHash);
        state.pendingApprovalTask = null;
        state.pendingApprovalHash = null;
        runs.record(state, EventTypes.APPROVAL_GRANTED, approvedTask + ":" + approvedHash + ":" + operator);
        LOG.info(
                "Run {} task {} approved by {}",
                LogText.singleLine(state.id),
                LogText.singleLine(approvedTask),
                LogText.singleLine(operator));
        return state;
    }

    /** The stored proposal must still hash to what was reviewed; a release must also still equal the candidate. */
    private boolean proposalIntact(RunState state, String reviewedHash) throws IOException, InterruptedException {
        Path proposal = evidence.currentOutput(state, state.pendingApprovalTask);
        TaskSpec reviewedTask = ScenarioFiles.read(Path.of(state.specPath)).spec().tasks().stream()
                .filter(task -> task.id().equals(state.pendingApprovalTask))
                .findFirst()
                .orElseThrow(() -> new WorkflowConflictException("Reviewed task no longer exists in the scenario"));
        if (!EvidenceIntegrity.isUnalteredFile(proposal)) return false;
        String reviewed = Files.readString(proposal);
        if (reviewedTask.kind() != TaskKind.RELEASE) {
            return Hashes.same(reviewedHash, ApprovalGate.patchHash(state, reviewed));
        }
        String diff = workspace.diff(Path.of(state.candidatePath), state.baselineCommit);
        return reviewed.equals(diff) && Hashes.same(reviewedHash, ApprovalGate.releaseHash(state, diff));
    }
}
