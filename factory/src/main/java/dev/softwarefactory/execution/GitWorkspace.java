package dev.softwarefactory.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Applies inert patches to a detached candidate, never to the reviewed branch. */
public final class GitWorkspace {
    /** Validator build output inside the candidate. Never part of a reviewed change. */
    public static final String BUILD_OUTPUT = "shortener/target";

    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern LINK_OR_SUBMODULE =
            Pattern.compile("(?:new file mode|old mode|new mode|deleted file mode|index [^ ]+) (?:120000|160000)");
    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9_./-]+");
    private static final Pattern RUN_ID = Pattern.compile("[a-f0-9-]{36}");
    private static final Pattern BASELINE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._/-]{0,79}");
    private static final Pattern NUMSTAT_FIELDS = Pattern.compile("\t");
    private static final Duration SHORT = Duration.ofSeconds(10);
    private static final Duration APPLY = Duration.ofSeconds(20);
    private static final Duration WORKTREE = Duration.ofSeconds(30);
    private static final Duration DIFF = Duration.ofSeconds(60);
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
        git(repository, WORKTREE, "worktree", "add", "--detach", candidate.toString(), baselineTag);
        return candidate;
    }

    public void removeOwned(Path candidate) throws IOException, InterruptedException {
        Path normalized = owned(candidate);
        git(repository, WORKTREE, "worktree", "remove", "--force", normalized.toString());
    }

    /** Drops administrative records of worktrees whose directories no longer exist. */
    public void pruneWorktrees() throws IOException, InterruptedException {
        git(repository, WORKTREE, "worktree", "prune");
    }

    public boolean isOwnedCandidate(Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        return runs.equals(normalized.getParent())
                && RUN_ID.matcher(normalized.getFileName().toString()).matches();
    }

    private Path owned(Path candidate) {
        if (!isOwnedCandidate(candidate))
            throw new PolicyViolationException("Can only remove this factory's run workspace");
        return candidate.toAbsolutePath().normalize();
    }

    public String resolveCommit(String baselineTag) throws IOException, InterruptedException {
        if (baselineTag == null || !BASELINE.matcher(baselineTag).matches())
            throw new IllegalArgumentException("Invalid baseline");
        return git(repository, SHORT, "rev-parse", "--verify", "--end-of-options", baselineTag + "^{commit}")
                .trim();
    }

    public void apply(Path candidate, String patch, List<String> allowed) throws IOException, InterruptedException {
        List<String> changed = validateScope(patch, allowed);
        Path file = Files.createTempFile("factory-patch-", ".diff");
        try {
            Files.writeString(file, patch);
            String actualPaths = git(candidate, APPLY, "apply", "--numstat", "-z", file.toString());
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
            git(candidate, APPLY, "apply", "--check", "--whitespace=error", file.toString());
            git(candidate, APPLY, "apply", "--whitespace=error", file.toString());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Check applicability without changing the candidate or its index. */
    public void checkApply(Path candidate, String patch, List<String> allowed)
            throws IOException, InterruptedException {
        validateScope(patch, allowed);
        Path file = Files.createTempFile("factory-preflight-", ".diff");
        try {
            Files.writeString(file, patch);
            git(candidate, APPLY, "apply", "--check", "--whitespace=error", file.toString());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    public List<String> validateScope(String patch, List<String> allowed) {
        if (patch.lines().anyMatch(line -> LINK_OR_SUBMODULE.matcher(line).matches())) {
            throw new PolicyViolationException("Symlinks and submodules are outside worker authority");
        }
        List<String> changed = new ArrayList<>();
        for (String line : patch.lines().toList()) {
            if (line.startsWith("rename ") || line.startsWith("copy ")) {
                throw new PolicyViolationException("Renames and copies are outside worker authority");
            }
            var header = DIFF_HEADER.matcher(line);
            boolean isHeader = header.matches();
            if (line.startsWith("diff --git ") && !isHeader)
                throw new PolicyViolationException("Ambiguous or quoted diff path");
            if (!isHeader) continue;
            if (!header.group(1).equals(header.group(2)))
                throw new PolicyViolationException("Renames are outside worker authority");
            String path = header.group(1);
            if (!SAFE_PATH.matcher(path).matches()
                    || path.startsWith("/")
                    || path.contains("..")
                    || isProtectedPath(path)
                    || allowed.stream().noneMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"))) {
                throw new PolicyViolationException("Patch outside approved scope: " + path);
            }
            changed.add(path);
        }
        if (changed.isEmpty()) throw new IllegalArgumentException("Patch has no recognized file changes");
        return changed;
    }

    /**
     * Paths a worker may never write, even inside its scope: build output (which Git ignores and the
     * validator overwrites), Git metadata and attributes that change how content is recorded or diffed,
     * environment files and logs. A {@code .gitignore} is allowed because release evidence is recorded
     * with ignore rules disabled; {@code PatchPolicy} still routes it to operator approval.
     */
    static boolean isProtectedPath(String path) {
        String[] segments = path.split("/");
        String name = segments[segments.length - 1].toLowerCase(Locale.ROOT);
        for (String segment : segments) {
            String lower = segment.toLowerCase(Locale.ROOT);
            if (lower.equals("target") || (lower.startsWith(".git") && !lower.equals(".gitignore"))) return true;
        }
        return name.equals(".env") || name.startsWith(".env.") || name.endsWith(".log");
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
                    DIFF,
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
                    DIFF,
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
        git(candidate, WORKTREE, "reset", "--hard", baselineCommit);
        git(candidate, WORKTREE, "clean", "-ffdx");
    }

    public static String command(Path directory, List<String> arguments, Duration timeout)
            throws IOException, InterruptedException {
        return CommandRunner.checked(directory, arguments, timeout);
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
