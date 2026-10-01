package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.platform.LogText;
import dev.softwarefactory.platform.WorkflowConflictException;
import dev.softwarefactory.scenario.Invalidation;
import dev.softwarefactory.scenario.ScenarioFiles;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Partial replan on operator request: the revised task and everything downstream of it become stale and
 * get a fresh retry budget, their patches leave the candidate, and unaffected work is preserved. A changed
 * scenario invalidates everything.
 */
final class ReviseRun {
    static final int MAX_FEEDBACK = 8_000;

    private static final Logger LOG = LoggerFactory.getLogger(ReviseRun.class);

    private final RunStore runs;
    private final CandidateRebuild candidates;
    private final String operator;

    ReviseRun(RunStore runs, CandidateRebuild candidates, String operator) {
        this.runs = runs;
        this.candidates = candidates;
        this.operator = operator;
    }

    static void requireUsableFeedback(String feedback) {
        if (feedback != null && (feedback.isBlank() || feedback.length() > MAX_FEEDBACK)) {
            throw new IllegalArgumentException("Review feedback must contain 1.." + MAX_FEEDBACK + " characters");
        }
    }

    RunState revise(String id, String taskId, String feedback) throws IOException, InterruptedException {
        RunState state = runs.load(id);
        if (state.status.isTerminal()) throw new WorkflowConflictException("Terminal runs cannot be revised");
        ScenarioFiles.Document scenario = ScenarioFiles.read(Path.of(state.specPath));
        ScenarioSpec spec = scenario.spec();
        if (spec.tasks().stream().noneMatch(task -> task.id().equals(taskId)))
            throw new IllegalArgumentException("Unknown task");
        if (feedback != null) {
            state.reviewFeedback.put(taskId, feedback);
            runs.record(
                    state,
                    EventTypes.REVIEW_FEEDBACK_RECORDED,
                    taskId + ":" + Hashes.sha256(feedback) + ":" + operator);
        }
        String specHash = Hashes.sha256(scenario.text());
        Set<String> affected = affectedTasks(state, spec, taskId, !Hashes.same(specHash, state.specHash));
        for (String task : affected) state.invalidate(task, TaskStatus.STALE);
        if (state.revisionRequiredTask != null && affected.contains(state.revisionRequiredTask))
            state.revisionRequiredTask = null;
        runs.record(state, EventTypes.ARTIFACTS_STALE, affected.toString());
        candidates.rebuild(state, spec, affected);
        requeue(state, spec, affected);
        state.pendingApprovalTask = null;
        state.pendingApprovalHash = null;
        state.pendingClarificationTask = null;
        state.requirementHash = Hashes.sha256(spec.requirement());
        state.specHash = specHash;
        state.status = RunStatus.PAUSED;
        runs.record(
                state,
                EventTypes.PARTIAL_REPLAN,
                "stale=" + affected + "; preserved=" + difference(state.tasks.keySet(), affected) + "; operator="
                        + operator);
        LOG.info(
                "Run {} revised from task {} by {}",
                LogText.singleLine(id),
                LogText.singleLine(taskId),
                LogText.singleLine(operator));
        return state;
    }

    private static Set<String> affectedTasks(RunState state, ScenarioSpec spec, String taskId, boolean specChanged) {
        if (!specChanged) return Invalidation.descendants(taskId, spec.tasks());
        Set<String> everything = new HashSet<>(state.tasks.keySet());
        everything.addAll(spec.tasks().stream().map(TaskSpec::id).toList());
        return everything;
    }

    /** Drops tasks the scenario no longer has and queues the affected ones again. */
    private static void requeue(RunState state, ScenarioSpec spec, Set<String> affected) {
        state.tasks
                .keySet()
                .removeIf(task ->
                        spec.tasks().stream().noneMatch(current -> current.id().equals(task)));
        for (TaskSpec task : spec.tasks()) {
            if (affected.contains(task.id())) state.tasks.put(task.id(), TaskStatus.PENDING);
        }
    }

    private static Set<String> difference(Set<String> all, Set<String> subset) {
        Set<String> result = new HashSet<>(all);
        result.removeAll(subset);
        return result;
    }
}
