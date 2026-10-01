package dev.softwarefactory.run;

import dev.softwarefactory.audit.EvidenceStore;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.generation.AgentRuntimes;
import dev.softwarefactory.platform.FactorySettings;
import dev.softwarefactory.validation.CandidateValidator;
import java.time.Clock;

/** Everything that can happen to a run, in story order, wired to one set of collaborators. */
record Transitions(
        StartRun start, AdvanceRun advance, ApproveStep approve, AnswerClarification clarify, ReviseRun revise) {
    static Transitions wire(
            RunStore runs,
            EvidenceStore evidenceStore,
            GitWorkspace workspace,
            CandidateValidator validator,
            AgentRuntimes agents,
            FactorySettings settings,
            Clock clock) {
        RunCore core = RunCore.of(runs, evidenceStore, clock);
        TaskGeneration generation = new TaskGeneration(runs, core.evidence(), agents);
        CandidateRebuild candidates = new CandidateRebuild(workspace, core.evidence());
        String operator = settings.operator();
        return new Transitions(
                new StartRun(runs, workspace, settings, clock),
                new AdvanceRun(
                        core,
                        new RecoverInterruptedRun(runs, core.evidence(), candidates),
                        new ParallelBranches(
                                runs, generation, core.evidence(), core.failures(), settings.runDeadline()),
                        TaskExecutors.forEveryKind(core, generation, workspace, validator)),
                new ApproveStep(core, workspace, operator),
                new AnswerClarification(runs, core.evidence(), operator),
                new ReviseRun(runs, candidates, operator));
    }
}
