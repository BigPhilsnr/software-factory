package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.platform.WorkflowConflictException;
import java.io.IOException;

/** Records the operator's answer to a pending clarification as the task's output, so the run can continue. */
final class AnswerClarification {
    private final RunStore runs;
    private final RunEvidence evidence;
    private final String operator;

    AnswerClarification(RunStore runs, RunEvidence evidence, String operator) {
        this.runs = runs;
        this.evidence = evidence;
        this.operator = operator;
    }

    RunState answer(String id, String answer) throws IOException {
        RunState state = runs.load(id);
        if (state.status != RunStatus.PAUSED || state.pendingClarificationTask == null || answer.isBlank()) {
            throw new WorkflowConflictException("No pending clarification or answer is blank");
        }
        String taskId = state.pendingClarificationTask;
        String hash = evidence.recordOutput(state, taskId, answer);
        state.pendingClarificationTask = null;
        runs.record(state, EventTypes.CLARIFICATION_RECORDED, taskId + ":" + hash + ":" + operator);
        return state;
    }
}
