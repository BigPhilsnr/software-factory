package dev.softwarefactory.run;

import dev.softwarefactory.candidate.DraftCheckFailedException;
import dev.softwarefactory.candidate.DraftWorkspace;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.governance.PatchScope;
import dev.softwarefactory.governance.PolicyViolationException;
import dev.softwarefactory.platform.GenerationUnavailableException;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import dev.softwarefactory.validation.CandidateValidator;
import dev.softwarefactory.validation.GeneratedTestPolicy;
import dev.softwarefactory.validation.ValidationFailedException;
import java.io.IOException;
import java.nio.file.Path;

/** Trusted checks of draft bytes, with a bounded number of sandbox executions and cached feedback. */
final class DraftChecks implements DraftWorkspace.Check {
    private static final int MAX_CHECKS = 3;
    private final RunState state;
    private final TaskSpec task;
    private final TaskKind validation;
    private final GitWorkspace workspace;
    private final CandidateValidator validator;
    private final RunStore runs;
    private int checks;
    private String lastHash;
    private String report;
    private DraftCheckFailedException failure;

    DraftChecks(
            RunState state,
            TaskSpec task,
            TaskKind validation,
            GitWorkspace workspace,
            CandidateValidator validator,
            RunStore runs) {
        this.state = state;
        this.task = task;
        this.validation = validation;
        this.workspace = workspace;
        this.validator = validator;
        this.runs = runs;
    }

    @Override
    public synchronized String check(Path directory, String diff) throws IOException, InterruptedException {
        if (diff.isBlank())
            throw new DraftCheckFailedException("Draft contains no changes; use the draft editing tools first");
        String hash = Hashes.sha256(diff);
        if (!Hashes.same(hash, lastHash)) inspect(directory, diff, hash);
        if (failure != null) {
            requireCapacity();
            throw failure;
        }
        return report;
    }

    /** Stop before another model pass when no further validation is possible in this attempt. */
    void requireCapacity() {
        if (checks >= MAX_CHECKS)
            throw new GenerationUnavailableException("Draft check limit reached; saved edits retained. "
                    + (failure == null ? "Resume to validate the latest edits." : failure.getMessage()));
    }

    private void inspect(Path directory, String diff, String hash) throws IOException, InterruptedException {
        PatchScope.changedPaths(diff, task.writeScope());
        workspace.checkApply(Path.of(state.candidatePath), diff, task.writeScope());
        requireCapacity();
        checks++;
        runs.record(state, "DRAFT_CHECK_STARTED", task.id() + ":" + hash + ":" + checks + "/" + MAX_CHECKS);
        failure = null;
        try {
            checkTestPolicy(diff);
            report = validate(directory, diff);
            runs.record(state, "DRAFT_CHECK_PASSED", task.id() + ":" + hash + ":" + report);
        } catch (ValidationFailedException | DraftCheckFailedException invalid) {
            String message = invalid.getMessage();
            failure = new DraftCheckFailedException(message.substring(Math.max(0, message.length() - 10000)));
            runs.record(
                    state,
                    "DRAFT_CHECK_FAILED",
                    task.id() + ":" + hash + ":diagnosticSha256=" + Hashes.sha256(message));
        }
        lastHash = hash;
    }

    /** Unapproved test content can be corrected; scope and integrity violations still fail closed. */
    private static void checkTestPolicy(String diff) {
        try {
            GeneratedTestPolicy.check(diff);
        } catch (PolicyViolationException invalidTest) {
            throw new DraftCheckFailedException("Draft test policy: " + invalidTest.getMessage(), invalidTest);
        }
    }

    private String validate(Path directory, String diff) throws IOException, InterruptedException {
        if (validation == TaskKind.VALIDATE_RED)
            return validator.expectRegression(directory, GeneratedTestPolicy.regressionTestClass(diff));
        if (validation == TaskKind.VALIDATE)
            return validator.runSuite(directory, GeneratedTestPolicy.changedTestClasses(diff));
        return "Scope, test policy and applicability passed; full suite deferred until downstream patches are complete";
    }
}
