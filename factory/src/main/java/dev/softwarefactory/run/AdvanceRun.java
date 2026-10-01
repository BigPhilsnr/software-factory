package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.platform.LogText;
import dev.softwarefactory.scenario.ScenarioFiles;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.TaskGraph;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The scheduler loop: repeatedly takes the tasks whose dependencies are done and executes them, until the
 * run completes, fails, or must wait for the operator or the platform.
 */
final class AdvanceRun {
    private static final Logger LOG = LoggerFactory.getLogger(AdvanceRun.class);

    private final RunStore runs;
    private final EvidenceIntegrity integrity;
    private final RecoverInterruptedRun recovery;
    private final ParallelBranches parallel;
    private final Map<TaskKind, TaskExecutor> executors;
    private final Clock clock;

    AdvanceRun(
            RunCore core,
            RecoverInterruptedRun recovery,
            ParallelBranches parallel,
            Map<TaskKind, TaskExecutor> executors) {
        this.runs = core.runs();
        this.integrity = core.integrity();
        this.recovery = recovery;
        this.parallel = parallel;
        this.executors = Map.copyOf(executors);
        this.clock = core.clock();
    }

    RunState advance(String id) throws IOException, InterruptedException {
        RunState state = runs.load(id);
        if (state.status.isTerminal() || !integrity.verify(state) || state.awaitsOperator()) return state;
        ScenarioFiles.Document scenario = ScenarioFiles.read(Path.of(state.specPath));
        ScenarioSpec spec = scenario.spec();
        TaskGraph graph = new TaskGraph(spec.tasks());
        if (!Hashes.same(Hashes.sha256(spec.requirement()), state.requirementHash)
                || !Hashes.same(Hashes.sha256(scenario.text()), state.specHash)) {
            state.status = RunStatus.PAUSED;
            runs.record(state, EventTypes.REPLAN_REQUIRED, "Requirement changed; explicit invalidation required");
            return state;
        }
        recovery.recover(state, spec);
        state.status = RunStatus.RUNNING;
        runs.record(state, EventTypes.RUN_RESUMED, state.scenario);
        LOG.info("Advancing run {}", LogText.singleLine(id));
        boolean proceed = true;
        while (proceed) {
            List<TaskSpec> ready = graph.ready(
                    task -> state.tasks.getOrDefault(task, TaskStatus.PENDING) == TaskStatus.PENDING,
                    task -> state.tasks.get(task) == TaskStatus.DONE);
            if (ready.isEmpty()) {
                finishIfIdle(state);
                return state;
            }
            proceed = executeNext(state, ready, spec);
        }
        return state;
    }

    /** Two ready, approval-free artifact tasks run as parallel branches; anything else runs one at a time. */
    private boolean executeNext(RunState state, List<TaskSpec> ready, ScenarioSpec spec)
            throws IOException, InterruptedException {
        List<TaskSpec> concurrent = ready.stream()
                .filter(task -> task.kind() == TaskKind.ARTIFACT && !task.requiresApproval())
                .limit(ParallelBranches.WIDTH)
                .toList();
        if (concurrent.size() == ParallelBranches.WIDTH) return parallel.execute(state, concurrent, spec);
        TaskSpec task = ready.getFirst();
        try (var ignoredContext = MDC.putCloseable(RunEngine.TASK_ID, task.id())) {
            return executors.get(task.kind()).execute(state, task, spec);
        }
    }

    private void finishIfIdle(RunState state) throws IOException {
        if (state.tasks.values().stream().allMatch(value -> value == TaskStatus.DONE)) {
            state.status = RunStatus.COMPLETED;
            state.finishedAt = clock.instant();
            runs.record(state, EventTypes.RUN_COMPLETED, "All tasks passed");
            LOG.info("Run {} completed", LogText.singleLine(state.id));
        } else if (state.status == RunStatus.RUNNING) {
            state.status = RunStatus.FAILED;
            state.finishedAt = clock.instant();
            runs.record(state, EventTypes.RUN_FAILED, "No READY task and incomplete graph");
            LOG.warn("Run {} failed: no ready task and incomplete graph", LogText.singleLine(state.id));
        }
    }
}
