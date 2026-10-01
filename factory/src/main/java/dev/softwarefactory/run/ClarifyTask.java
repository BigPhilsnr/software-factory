package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;

/** CLARIFY: the requirement is ambiguous, so the run pauses until the operator answers the question. */
final class ClarifyTask implements TaskExecutor {
    private final RunStore runs;

    ClarifyTask(RunStore runs) {
        this.runs = runs;
    }

    @Override
    public boolean execute(RunState state, TaskSpec task, ScenarioSpec spec) throws IOException {
        state.pendingClarificationTask = task.id();
        state.status = RunStatus.PAUSED;
        runs.record(state, EventTypes.CLARIFICATION_REQUIRED, task.id() + ":" + task.prompt());
        return false;
    }
}
