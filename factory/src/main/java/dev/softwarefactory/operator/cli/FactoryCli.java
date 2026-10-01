package dev.softwarefactory.operator.cli;

import dev.softwarefactory.agents.ModelClients;
import dev.softwarefactory.configuration.FactorySettings;
import dev.softwarefactory.execution.GitWorkspace;
import dev.softwarefactory.observability.RunMetrics;
import dev.softwarefactory.persistence.AuditKey;
import dev.softwarefactory.persistence.ControlRepository;
import dev.softwarefactory.serialization.Json;
import dev.softwarefactory.workflow.RunEngine;
import dev.softwarefactory.workflow.RunState;
import dev.softwarefactory.workflow.RunStatus;
import dev.softwarefactory.workflow.scenario.ScenarioFiles;
import dev.softwarefactory.workflow.scenario.ScenarioSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Operator CLI. Runs require a separate control database inaccessible to workers. */
public final class FactoryCli {
    private static final int USAGE_EXIT = 2;
    private static final int DEFAULT_PRUNE_DAYS = 30;
    private static final Set<RunStatus> TERMINAL =
            Set.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.SAFE_STOPPED, RunStatus.NOT_APPROVED);

    private FactoryCli() {}

    /** Thrown for malformed arguments; reported as usage with exit code 2. */
    private static final class UsageException extends Exception {
        UsageException() {
            super("usage");
        }
    }

    public static void main(String[] args) throws Exception {
        // CLI stdout is a machine-readable JSON protocol; diagnostics belong on stderr.
        System.setProperty(
                "logback.configurationFile",
                FactoryCli.class.getResource("/factory-logback.xml").toExternalForm());
        try {
            run(args);
        } catch (UsageException invalid) {
            usage();
            System.exit(USAGE_EXIT);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 1) throw new UsageException();
        FactorySettings settings = FactorySettings.fromEnvironment();
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName().toString().equals("factory")) root = root.getParent();
        var repo = new ControlRepository(
                settings.controlDatabaseUrl(),
                settings.controlDatabaseUser(),
                settings.controlDatabasePassword(),
                AuditKey.resolve(settings.auditKey(), root));
        repo.initialize();
        try (var clients = new ModelClients(settings)) {
            RunEngine engine = new RunEngine(repo, root, settings, clients);
            Object result = switch (args[0]) {
                case "start" -> engine.start(Path.of(arity(args, 3)[1]), args[2]);
                case "advance" -> engine.advance(arity(args, 2)[1]);
                case "approve" -> engine.approve(arity(args, 3)[1], args[2], true);
                case "reject" -> engine.approve(arity(args, 3)[1], args[2], false);
                case "clarify" -> {
                    if (args.length < 3) throw new UsageException();
                    yield engine.clarify(args[1], String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                }
                case "revise" -> {
                    if (args.length < 3 || args.length > 4) throw new UsageException();
                    yield engine.revise(args[1], args[2], args.length == 4 ? Files.readString(Path.of(args[3])) : null);
                }
                case "status" -> repo.load(arity(args, 2)[1]);
                case "review" -> review(repo, root, arity(args, 2)[1]);
                case "metrics" -> RunMetrics.from(repo.load(arity(args, 2)[1]), repo.timeline(args[1]), Instant.now());
                case "verify-audit" -> repo.auditValid(arity(args, 2)[1]) ? "AUDIT_VALID" : "AUDIT_INVALID";
                case "prune" -> prune(repo, root, args);
                default -> throw new UsageException();
            };
            System.out.println(
                    result instanceof String text
                            ? text
                            : Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        }
    }

    private static String[] arity(String[] args, int expected) throws UsageException {
        if (args.length != expected) throw new UsageException();
        return args;
    }

    private static Map<String, Object> review(ControlRepository repo, Path root, String id) throws IOException {
        RunState state = repo.load(id);
        if (state.pendingApprovalTask == null) throw new IllegalStateException("No pending approval");
        ScenarioSpec spec = Json.MAPPER.readValue(
                Files.readString(ScenarioFiles.resolve(Path.of(state.specPath))), ScenarioSpec.class);
        var task = spec.tasks().stream()
                .filter(item -> item.id().equals(state.pendingApprovalTask))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Pending task is not in the scenario"));
        Path proposal = root.resolve("evidence")
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
     * Removes candidate worktrees and evidence of terminal runs that finished more than N days ago.
     * Dry-run unless {@code --apply}. Database state and the audit chain are kept.
     */
    private static Map<String, Object> prune(ControlRepository repo, Path root, String[] args) throws Exception {
        int days = DEFAULT_PRUNE_DAYS;
        boolean apply = false;
        for (int index = 1; index < args.length; index++) {
            switch (args[index]) {
                case "--apply" -> apply = true;
                case "--days" -> {
                    if (index + 1 >= args.length) throw new UsageException();
                    days = parseDays(args[++index]);
                }
                default -> throw new UsageException();
            }
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        GitWorkspace workspace = new GitWorkspace(root);
        List<Map<String, Object>> pruned = new ArrayList<>();
        for (RunState state : repo.runsUpdatedBefore(cutoff)) {
            if (!TERMINAL.contains(state.status) || state.finishedAt == null || !state.finishedAt.isBefore(cutoff))
                continue;
            Path candidate = state.candidatePath == null ? null : Path.of(state.candidatePath);
            boolean ownsCandidate =
                    candidate != null && Files.isDirectory(candidate) && workspace.isOwnedCandidate(candidate);
            Path evidence = root.resolve("evidence").resolve(state.id);
            boolean hasEvidence = Files.isDirectory(evidence) && !Files.isSymbolicLink(evidence);
            if (!ownsCandidate && !hasEvidence) continue;
            if (apply) {
                if (ownsCandidate) workspace.removeOwned(candidate);
                if (hasEvidence) deleteRecursively(evidence);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", state.id);
            entry.put("status", state.status.toString());
            entry.put("finishedAt", state.finishedAt.toString());
            entry.put("candidate", ownsCandidate ? candidate.toString() : null);
            entry.put("evidence", hasEvidence ? evidence.toString() : null);
            pruned.add(entry);
        }
        if (apply) workspace.pruneWorktrees();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applied", apply);
        result.put("cutoff", cutoff.toString());
        result.put("runs", pruned);
        return result;
    }

    private static int parseDays(String value) throws UsageException {
        try {
            int days = Integer.parseInt(value);
            if (days < 0) throw new UsageException();
            return days;
        } catch (NumberFormatException invalid) {
            throw new UsageException();
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static void usage() {
        System.err.println(
                "Usage: start <scenario.json> <fixture|live> | advance <run-id> | review <run-id> | approve <run-id> <reviewed-hash>"
                        + " | reject <run-id> <reviewed-hash> | clarify <run-id> <answer> | revise <run-id> <task-id> [feedback-file] | status <run-id>"
                        + " | metrics <run-id> | verify-audit <run-id> | prune [--days N] [--apply]");
    }
}
