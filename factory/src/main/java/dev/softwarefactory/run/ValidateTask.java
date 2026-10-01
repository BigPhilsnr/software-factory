package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import dev.softwarefactory.validation.CandidateValidator;
import dev.softwarefactory.validation.GeneratedTestPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * VALIDATE / VALIDATE_RED: proves the candidate in the sandbox. A green suite pins the hash of the
 * validated candidate, which a later release must still match.
 */
final class ValidateTask implements TaskExecutor {
    private final RunStore runs;
    private final RunEvidence evidence;
    private final GitWorkspace workspace;
    private final CandidateValidator validator;
    private final FailureHandling failures;

    ValidateTask(
            RunStore runs,
            RunEvidence evidence,
            GitWorkspace workspace,
            CandidateValidator validator,
            FailureHandling failures) {
        this.runs = runs;
        this.evidence = evidence;
        this.workspace = workspace;
        this.validator = validator;
        this.failures = failures;
    }

    @Override
    public boolean execute(RunState state, TaskSpec task, ScenarioSpec spec) throws IOException, InterruptedException {
        return failures.attempt(state, task, () -> {
            state.tasks.put(task.id(), TaskStatus.RUNNING);
            runs.record(state, EventTypes.VALIDATION_STARTED, task.id());
            Path candidate = Path.of(state.candidatePath);
            String report = task.kind() == TaskKind.VALIDATE_RED
                    ? validator.expectRegression(candidate, regressionTestClass(state, task))
                    : validator.runSuite(candidate, changedTestClasses(state, task, spec));
            evidence.complete(state, task, report);
            if (task.kind() == TaskKind.VALIDATE) {
                state.validatedCandidateHash = Hashes.sha256(workspace.diff(candidate, state.baselineCommit));
                runs.record(state, EventTypes.CANDIDATE_VALIDATED, state.validatedCandidateHash);
            }
        });
    }

    /** Test classes added or changed by completed upstream patches; each must actually execute. */
    private Set<String> changedTestClasses(RunState state, TaskSpec validation, ScenarioSpec spec) throws IOException {
        Map<String, TaskSpec> byId = new HashMap<>();
        for (TaskSpec task : spec.tasks()) byId.put(task.id(), task);
        Set<String> classes = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(validation.dependsOn());
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            TaskSpec ancestor = byId.get(id);
            if (ancestor == null || !seen.add(id)) continue;
            queue.addAll(ancestor.dependsOn());
            Integer version = state.artifactVersions.get(id);
            if (ancestor.kind() == TaskKind.PATCH && version != null && state.tasks.get(id) == TaskStatus.DONE) {
                classes.addAll(GeneratedTestPolicy.changedTestClasses(evidence.readOutput(state, id, version)));
            }
        }
        return classes;
    }

    private String regressionTestClass(RunState state, TaskSpec task) throws IOException {
        if (task.dependsOn().size() != 1)
            throw new IllegalArgumentException("Red validation requires one test patch dependency");
        String dependency = task.dependsOn().getFirst();
        Integer version = state.artifactVersions.get(dependency);
        if (version == null) throw new IllegalStateException("Regression test patch is missing");
        return GeneratedTestPolicy.regressionTestClass(evidence.readOutput(state, dependency, version));
    }
}
