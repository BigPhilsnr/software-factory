package dev.softwarefactory.candidate;

import dev.softwarefactory.governance.PatchScope;
import dev.softwarefactory.governance.PolicyViolationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The isolated working copy of one run: a detached Git worktree under {@code .runs/}. Inert patches are
 * applied here, never to the reviewed branch.
 */
public final class GitWorkspace {
    /** Validator build output inside the candidate. Never part of a reviewed change. */
    public static final String BUILD_OUTPUT = "shortener/target";

    private static final Pattern RUN_ID = Pattern.compile("[a-f0-9-]{36}");
    private static final Pattern BASELINE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._/-]{0,79}");
    private static final Pattern NUMSTAT_FIELDS = Pattern.compile("\t");
    private static final String WORKTREE = "worktree";
    private static final String APPLY = "apply";
    private static final String STRICT_WHITESPACE = "--whitespace=error";
    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration APPLY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration WORKTREE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DIFF_TIMEOUT = Duration.ofSeconds(60);
    /** Host Git must not run repository-controlled hooks or monitors; diffs also disable external drivers and text conversion. */
    private static final List<String> GIT =
            List.of("git", "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false", "-c", "core.quotePath=true");

    private final Path repository;
    private final Path runs;

    public GitWorkspace(Path repository) {
        this.repository = repository.toAbsolutePath().normalize();
        this.runs = this.repository.resolve(".runs");
    }

    public Path create(String runId, String baselineTag) throws IOException, InterruptedException {
        if (!RUN_ID.matcher(runId).matches() || !BASELINE.matcher(baselineTag).matches()) {
            throw new IllegalArgumentException("Invalid run or baseline");
        }
        Files.createDirectories(runs);
        Path candidate = runs.resolve(runId);
        git(repository, WORKTREE_TIMEOUT, WORKTREE, "add", "--detach", candidate.toString(), baselineTag);
        return candidate;
    }

    public void removeOwned(Path candidate) throws IOException, InterruptedException {
        Path normalized = owned(candidate);
        git(repository, WORKTREE_TIMEOUT, WORKTREE, "remove", "--force", normalized.toString());
    }

    /** Drops administrative records of worktrees whose directories no longer exist. */
    public void pruneWorktrees() throws IOException, InterruptedException {
        git(repository, WORKTREE_TIMEOUT, WORKTREE, "prune");
    }

    public boolean isOwnedCandidate(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        Path name = normalized.getFileName();
        return name != null
                && runs.equals(normalized.getParent())
                && RUN_ID.matcher(name.toString()).matches();
    }

    private Path owned(Path candidate) {
        if (!isOwnedCandidate(candidate))
            throw new PolicyViolationException("Can only remove this factory's run workspace");
        return candidate.toAbsolutePath().normalize();
    }

    public String resolveCommit(String baselineTag) throws IOException, InterruptedException {
        if (baselineTag == null || !BASELINE.matcher(baselineTag).matches())
            throw new IllegalArgumentException("Invalid baseline");
        return git(repository, SHORT_TIMEOUT, "rev-parse", "--verify", "--end-of-options", baselineTag + "^{commit}")
                .trim();
    }

    public void apply(Path candidate, String patch, List<String> allowed) throws IOException, InterruptedException {
        List<String> changed = PatchScope.changedPaths(patch, allowed);
        Path file = Files.createTempFile("factory-patch-", ".diff");
        try {
            Files.writeString(file, patch);
            String actualPaths = git(candidate, APPLY_TIMEOUT, APPLY, "--numstat", "-z", file.toString());
            List<String> actual = Arrays.stream(actualPaths.split("\u0000"))
                    .filter(line -> !line.isBlank())
                    .map(line -> {
                        String[] fields = NUMSTAT_FIELDS.split(line, 3);
                        if (fields.length != 3) throw new PolicyViolationException("Unparseable patch path");
                        return fields[2];
                    })
                    .toList();
            if (actual.isEmpty() || !changed.containsAll(actual) || !actual.containsAll(changed)) {
                throw new PolicyViolationException("Patch paths do not match the reviewed diff headers");
            }
            git(candidate, APPLY_TIMEOUT, APPLY, "--check", STRICT_WHITESPACE, file.toString());
            git(candidate, APPLY_TIMEOUT, APPLY, STRICT_WHITESPACE, file.toString());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Check applicability without changing the candidate or its index. */
    public void checkApply(Path candidate, String patch, List<String> allowed)
            throws IOException, InterruptedException {
        PatchScope.changedPaths(patch, allowed);
        Path file = Files.createTempFile("factory-preflight-", ".diff");
        try {
            Files.writeString(file, patch);
            git(candidate, APPLY_TIMEOUT, APPLY, "--check", STRICT_WHITESPACE, file.toString());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * The exact candidate change relative to its baseline, including files Git would ignore.
     * A private index keeps the candidate's own index untouched and makes ignore rules irrelevant.
     */
    public String diff(Path candidate, String baselineCommit) throws IOException, InterruptedException {
        Path indexDirectory = Files.createTempDirectory("factory-index-");
        try {
            Map<String, String> privateIndex =
                    Map.of("GIT_INDEX_FILE", indexDirectory.resolve("index").toString());
            git(
                    candidate,
                    privateIndex,
                    DIFF_TIMEOUT,
                    CommandRunner.DEFAULT_OUTPUT_LIMIT,
                    "add",
                    "--all",
                    "--force",
                    "--",
                    ".",
                    ":(top,exclude)" + BUILD_OUTPUT);
            return git(
                    candidate,
                    privateIndex,
                    DIFF_TIMEOUT,
                    CommandRunner.DATA_OUTPUT_LIMIT,
                    "diff",
                    "--cached",
                    "--binary",
                    "--no-ext-diff",
                    "--no-textconv",
                    "--no-renames",
                    baselineCommit,
                    "--");
        } finally {
            deleteRecursively(indexDirectory);
        }
    }

    /** Restore the baseline exactly, removing untracked and ignored files including stale build output. */
    public void reset(Path candidate, String baselineCommit) throws IOException, InterruptedException {
        git(candidate, WORKTREE_TIMEOUT, "reset", "--hard", baselineCommit);
        git(candidate, WORKTREE_TIMEOUT, "clean", "-ffdx");
    }

    private static String git(Path directory, Duration timeout, String... arguments)
            throws IOException, InterruptedException {
        return git(directory, Map.of(), timeout, CommandRunner.DEFAULT_OUTPUT_LIMIT, arguments);
    }

    private static String git(
            Path directory, Map<String, String> environment, Duration timeout, int outputLimit, String... arguments)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(GIT);
        command.addAll(List.of(arguments));
        return CommandRunner.checked(CommandRunner.Invocation.of(directory, command, timeout)
                .withEnvironment(environment)
                .withOutputLimit(outputLimit, CommandRunner.Overflow.FAIL));
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
