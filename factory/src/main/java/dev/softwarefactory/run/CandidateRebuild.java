package dev.softwarefactory.run;

import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/** Returns the candidate to a known state: its baseline plus every completed patch that is still valid. */
final class CandidateRebuild {
    private final GitWorkspace workspace;
    private final RunEvidence evidence;

    CandidateRebuild(GitWorkspace workspace, RunEvidence evidence) {
        this.workspace = workspace;
        this.evidence = evidence;
    }

    /**
     * @param excluded tasks whose patches must not be re-applied
     */
    void rebuild(RunState state, ScenarioSpec spec, Set<String> excluded) throws IOException, InterruptedException {
        Path candidate = Path.of(state.candidatePath);
        workspace.reset(candidate, state.baselineCommit);
        for (TaskSpec task : spec.tasks()) {
            if (!excluded.contains(task.id())
                    && task.kind() == TaskKind.PATCH
                    && state.tasks.get(task.id()) == TaskStatus.DONE) {
                workspace.apply(candidate, evidence.readCurrentOutput(state, task.id()), task.writeScope());
            }
        }
        state.validatedCandidateHash = null;
    }
}
