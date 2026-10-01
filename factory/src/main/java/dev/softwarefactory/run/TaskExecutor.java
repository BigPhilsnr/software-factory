package dev.softwarefactory.run;

import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;

/** Carries out one ready task of a run. Every state change is committed to the run store before returning. */
@FunctionalInterface
interface TaskExecutor {
    /**
     * @return true when the task is done and the scheduler may continue; false when the run now waits
     *     (for the operator or the platform) or has ended
     */
    boolean execute(RunState state, TaskSpec task, ScenarioSpec spec) throws IOException, InterruptedException;
}
