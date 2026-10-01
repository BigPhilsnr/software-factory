package dev.softwarefactory.operator.cli;

import dev.softwarefactory.audit.AuditTrail;
import dev.softwarefactory.audit.RunMetrics;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.run.DurableRunStore;
import dev.softwarefactory.run.RunEngine;
import dev.softwarefactory.run.RunState;
import dev.softwarefactory.scenario.ScenarioFiles;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** What the CLI can do: the engine's transitions, read-only inspection, and pruning of old run workspaces. */
final class CliActions {
    private final RunEngine engine;
    private final DurableRunStore runs;
    private final AuditTrail trail;
    private final GitWorkspace workspace;
    private final Path evidenceRoot;

    CliActions(RunEngine engine, DurableRunStore runs, AuditTrail trail, Path root) {
        this.engine = engine;
        this.runs = runs;
        this.trail = trail;
        this.workspace = new GitWorkspace(root);
        this.evidenceRoot = root.resolve("evidence");
    }

    RunState start(Path scenario, String mode) throws IOException, InterruptedException {
        return engine.start(scenario, mode);
    }

    RunState advance(String runId) throws IOException, InterruptedException {
        return engine.advance(runId);
    }

    RunState decide(String runId, String reviewedHash, boolean accepted) throws IOException, InterruptedException {
        return engine.approve(runId, reviewedHash, accepted);
    }

    RunState clarify(String runId, String answer) throws IOException, InterruptedException {
        return engine.clarify(runId, answer);
    }

    /**
     * @param feedbackFile a file holding the operator's feedback, or null to revise without feedback
     */
    RunState revise(String runId, String task, Path feedbackFile) throws IOException, InterruptedException {
        return engine.revise(runId, task, feedbackFile == null ? null : Files.readString(feedbackFile));
    }

    RunState status(String runId) throws IOException {
        return runs.load(runId);
    }

    RunMetrics metrics(String runId) throws IOException {
        RunState state = runs.load(runId);
        return RunMetrics.from(state.startedAt, state.finishedAt, trail.timeline(runId), Instant.now());
    }

    String verifyAudit(String runId) throws IOException {
        return runs.auditValid(runId) ? "AUDIT_VALID" : "AUDIT_INVALID";
    }

    /** What the operator must look at before approving: the proposal file and the exact hash to approve. */
    Map<String, Object> review(String runId) throws IOException {
        RunState state = runs.load(runId);
        if (state.pendingApprovalTask == null) throw new IllegalStateException("No pending approval");
        TaskSpec task = ScenarioFiles.read(Path.of(state.specPath)).spec().tasks().stream()
                .filter(item -> item.id().equals(state.pendingApprovalTask))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Pending task is not in the scenario"));
        Path proposal = evidenceRoot
                .resolve(state.id)
                .resolve(task.id() + "-v" + state.artifactVersions.get(task.id()) + ".txt");
        return Map.of(
                "runId",
                state.id,
                "task",
                task.id(),
                "reviewedHash",
                state.pendingApprovalHash,
                "baselineCommit",
                state.baselineCommit == null ? state.baselineTag : state.baselineCommit,
                "writeScope",
                task.writeScope(),
                "proposal",
                Files.exists(proposal) ? proposal.toString() : "none",
                "validatedCandidateHash",
                state.validatedCandidateHash == null ? "not yet validated" : state.validatedCandidateHash);
    }

    /**
     * Removes candidate worktrees and evidence of terminal runs that finished more than {@code days} ago.
     * Lists only unless {@code apply}. Database state and the audit chain are kept.
     */
    Map<String, Object> prune(int days, boolean apply) throws IOException, InterruptedException {
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        List<Map<String, Object>> pruned = new ArrayList<>();
        for (RunState state : runs.runsUpdatedBefore(cutoff)) {
            if (state.status.isTerminal() && state.finishedAt != null && state.finishedAt.isBefore(cutoff)) {
                pruneRun(state, apply).ifPresent(pruned::add);
            }
        }
        if (apply) workspace.pruneWorktrees();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applied", apply);
        result.put("cutoff", cutoff.toString());
        result.put("runs", pruned);
        return result;
    }

    /** The report entry of one prunable run, or empty when it has neither a candidate nor evidence left. */
    private Optional<Map<String, Object>> pruneRun(RunState state, boolean apply)
            throws IOException, InterruptedException {
        Path candidate = ownedCandidate(state);
        Path evidence = evidenceFolder(state);
        if (candidate == null && evidence == null) return Optional.empty();
        if (apply) {
            if (candidate != null) workspace.removeOwned(candidate);
            if (evidence != null) deleteRecursively(evidence);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", state.id);
        entry.put("status", state.status.toString());
        entry.put("finishedAt", state.finishedAt.toString());
        entry.put("candidate", Objects.toString(candidate, null));
        entry.put("evidence", Objects.toString(evidence, null));
        return Optional.of(entry);
    }

    /** The run's candidate worktree if it still exists and is one this factory created; otherwise null. */
    private Path ownedCandidate(RunState state) {
        if (state.candidatePath == null) return null;
        Path candidate = Path.of(state.candidatePath);
        return Files.isDirectory(candidate) && workspace.isOwnedCandidate(candidate) ? candidate : null;
    }

    private Path evidenceFolder(RunState state) {
        Path evidence = evidenceRoot.resolve(state.id);
        return Files.isDirectory(evidence) && !Files.isSymbolicLink(evidence) ? evidence : null;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
