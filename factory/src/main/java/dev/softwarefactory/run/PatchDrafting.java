package dev.softwarefactory.run;

import dev.softwarefactory.candidate.DraftCheckFailedException;
import dev.softwarefactory.candidate.DraftCheckpoint;
import dev.softwarefactory.candidate.DraftWorkspace;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.generation.AgentRuntime;
import dev.softwarefactory.generation.UntrustedText;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.governance.PolicyViolationException;
import dev.softwarefactory.platform.GenerationUnavailableException;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import dev.softwarefactory.validation.CandidateValidator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/** Bounded drafting/repair passes; Git, not the final model response, supplies the reviewed artifact. */
final class PatchDrafting {
    private static final int MAX_PASSES = 3;
    private final RunStore runs;
    private final GitWorkspace workspace;
    private final CandidateValidator validator;

    PatchDrafting(RunStore runs, GitWorkspace workspace, CandidateValidator validator) {
        this.runs = runs;
        this.workspace = workspace;
        this.validator = validator;
    }

    String generate(
            RunState state,
            TaskSpec task,
            ScenarioSpec spec,
            Generation generation,
            Function<Path, AgentRuntime> runtime)
            throws IOException, InterruptedException {
        var checks = new DraftChecks(state, task, validation(task, spec), workspace, validator, runs);
        var draft = open(state, task, checks);
        String feedback = "";
        String previousFailureHash = null;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            runtime.apply(draft.path())
                    .draft(
                            generation.role(),
                            generation.prompt()
                                    + "\nExisting draft changes are preserved. Inspect them before editing.\n"
                                    + UntrustedText.block("Draft repair feedback", feedback),
                            draft);
            String diff = draft.diff();
            try {
                draft.check();
                return diff;
            } catch (DraftCheckFailedException invalid) {
                feedback = invalid.getMessage();
                String hash = Hashes.sha256(diff);
                if (Hashes.same(hash, previousFailureHash))
                    throw new GenerationUnavailableException(
                            "Draft repair made no progress; saved edits retained. " + feedback, invalid);
                previousFailureHash = hash;
            }
        }
        throw new GenerationUnavailableException("Draft repair limit reached; saved edits retained. " + feedback);
    }

    private DraftWorkspace open(RunState state, TaskSpec task, DraftChecks checks)
            throws IOException, InterruptedException {
        String inputHash = Hashes.sha256(state.specHash
                + state.requirementHash
                + state.baselineCommit
                + new TreeMap<>(state.artifactHashes)
                + state.reviewFeedback.get(task.id())
                + workspace.diff(Path.of(state.candidatePath), state.baselineCommit));
        Path parent = java.util.Objects.requireNonNull(
                        Path.of(state.candidatePath).getParent())
                .resolve("drafts")
                .toAbsolutePath()
                .normalize();
        DraftCheckpoint saved = state.drafting.get(task.id());
        if (saved == null || !Hashes.same(inputHash, saved.inputHash())) {
            Path directory = parent.resolve(state.id + "-" + task.id() + "-" + UUID.randomUUID());
            saved = DraftWorkspace.create(Path.of(state.candidatePath), directory, inputHash);
            save(state, task, saved);
        }
        Path directory = Path.of(saved.directory()).toAbsolutePath().normalize();
        if (!parent.equals(directory.getParent()))
            throw new PolicyViolationException("Draft directory escaped owned storage");
        var draft = new DraftWorkspace(
                saved, workspace, task.writeScope(), checkpoint -> save(state, task, checkpoint), checks);
        draft.verify();
        return draft;
    }

    private void save(RunState state, TaskSpec task, DraftCheckpoint checkpoint) throws IOException {
        state.drafting.put(task.id(), checkpoint);
        runs.record(state, "DRAFT_CHECKPOINT", task.id() + ":" + checkpoint.diffHash());
    }

    private static TaskKind validation(TaskSpec task, ScenarioSpec spec) {
        return spec.tasks().stream()
                .filter(next -> next.kind().isValidation() && next.dependsOn().contains(task.id()))
                .map(TaskSpec::kind)
                .findFirst()
                .orElse(null);
    }
}
