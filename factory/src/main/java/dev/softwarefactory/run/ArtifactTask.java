package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.util.Optional;

/** ARTIFACT: an agent writes a document (requirements, design, plan, ...) that becomes immutable evidence. */
final class ArtifactTask implements TaskExecutor {
    private final RunStore runs;
    private final TaskGeneration generation;
    private final RunEvidence evidence;
    private final FailureHandling failures;

    ArtifactTask(RunStore runs, TaskGeneration generation, RunEvidence evidence, FailureHandling failures) {
        this.runs = runs;
        this.generation = generation;
        this.evidence = evidence;
        this.failures = failures;
    }

    @Override
    public boolean execute(RunState state, TaskSpec task, ScenarioSpec spec) throws IOException, InterruptedException {
        Optional<String> output = failures.produce(state, task, () -> {
            state.tasks.put(task.id(), TaskStatus.RUNNING);
            runs.record(state, EventTypes.TASK_STARTED, task.id());
            return generation.generate(state, task, generation.prepare(state, task, spec));
        });
        if (output.isEmpty()) return false;
        evidence.complete(state, task, output.get());
        return true;
    }
}
