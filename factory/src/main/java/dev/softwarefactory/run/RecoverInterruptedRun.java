package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.scenario.Invalidation;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Repairs a run whose process stopped mid-task. Tasks left RUNNING either adopt the output they had
 * already written or are queued again; an interrupted patch also resets the candidate and everything
 * downstream of it, because the working copy may be half-changed.
 */
final class RecoverInterruptedRun {
    private final RunStore runs;
    private final RunEvidence evidence;
    private final CandidateRebuild candidates;

    RecoverInterruptedRun(RunStore runs, RunEvidence evidence, CandidateRebuild candidates) {
        this.runs = runs;
        this.evidence = evidence;
        this.candidates = candidates;
    }

    void recover(RunState state, ScenarioSpec spec) throws IOException, InterruptedException {
        List<TaskSpec> interrupted = spec.tasks().stream()
                .filter(task -> state.tasks.get(task.id()) == TaskStatus.RUNNING)
                .toList();
        if (interrupted.isEmpty()) return;
        List<String> interruptedIds = interrupted.stream().map(TaskSpec::id).toList();
        boolean resetCandidate = interrupted.stream().anyMatch(task -> task.kind() == TaskKind.PATCH);
        for (TaskSpec task : interrupted) adoptOutputOrRequeue(state, task);
        if (resetCandidate) {
            Set<String> downstream = downstreamOfPatches(interrupted, spec);
            for (String task : downstream) state.invalidate(task, TaskStatus.PENDING);
            // Revision-fenced: if another process changed the run, this write fails before the reset.
            runs.record(
                    state, EventTypes.RUN_RECOVERING, "interrupted=" + interruptedIds + "; invalidated=" + downstream);
            candidates.rebuild(state, spec, Set.of());
        }
        runs.record(
                state,
                EventTypes.RUN_RECOVERED,
                "interrupted=" + interruptedIds + "; candidateReset=" + resetCandidate);
    }

    /** A generation that finished writing its evidence before the stop is not paid for twice. */
    private void adoptOutputOrRequeue(RunState state, TaskSpec task) throws IOException {
        int nextVersion = state.artifactVersions.getOrDefault(task.id(), 0) + 1;
        Path completedOutput = evidence.output(state, task.id(), nextVersion);
        if (Files.isRegularFile(completedOutput)) {
            state.artifactVersions.put(task.id(), nextVersion);
            if (task.kind() == TaskKind.ARTIFACT || task.kind() == TaskKind.PATCH) {
                state.artifactHashes.put(task.id(), Hashes.sha256(Files.readAllBytes(completedOutput)));
                state.tasks.put(task.id(), TaskStatus.DONE);
                return;
            }
        }
        state.tasks.put(task.id(), TaskStatus.PENDING);
    }

    private static Set<String> downstreamOfPatches(List<TaskSpec> interrupted, ScenarioSpec spec) {
        Set<String> downstream = new HashSet<>();
        for (TaskSpec task : interrupted) {
            if (task.kind() == TaskKind.PATCH) {
                Set<String> affected = Invalidation.descendants(task.id(), spec.tasks());
                affected.remove(task.id());
                downstream.addAll(affected);
            }
        }
        return downstream;
    }
}
