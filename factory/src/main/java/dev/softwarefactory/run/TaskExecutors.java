package dev.softwarefactory.run;

import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.validation.CandidateValidator;
import java.util.EnumMap;
import java.util.Map;

/** The executor for each kind of task: the one place that says how a task kind is carried out. */
final class TaskExecutors {
    private TaskExecutors() {}

    static Map<TaskKind, TaskExecutor> forEveryKind(
            RunCore core, TaskGeneration generation, GitWorkspace workspace, CandidateValidator validator) {
        RunStore runs = core.runs();
        ApprovalGate gate = new ApprovalGate(runs, core.evidence());
        TaskExecutor validate = new ValidateTask(runs, core.evidence(), workspace, validator, core.failures());
        Map<TaskKind, TaskExecutor> executors = new EnumMap<>(TaskKind.class);
        executors.put(TaskKind.CLARIFY, new ClarifyTask(runs));
        executors.put(TaskKind.ARTIFACT, new ArtifactTask(runs, generation, core.evidence(), core.failures()));
        executors.put(
                TaskKind.PATCH, new PatchTask(runs, generation, core.evidence(), workspace, gate, core.failures()));
        executors.put(TaskKind.VALIDATE, validate);
        executors.put(TaskKind.VALIDATE_RED, validate);
        executors.put(TaskKind.RELEASE, new ReleaseTask(runs, workspace, core.integrity(), gate));
        return executors;
    }
}
