package dev.softwarefactory.execution;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Applies inert patches to a detached candidate, never to the reviewed branch. */
public final class GitWorkspace {
    private static final Pattern DIFF = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private final Path repository;
    private final Path runs;

    public GitWorkspace(Path repository) {
        this.repository = repository.toAbsolutePath().normalize();
        this.runs = this.repository.resolve(".runs");
    }

    public Path create(String runId, String baselineTag) throws Exception {
        if (!runId.matches("[a-f0-9-]{36}") || !baselineTag.matches("[a-zA-Z0-9][a-zA-Z0-9._/-]{0,79}")) {
            throw new IllegalArgumentException("Invalid run or baseline");
        }
        Files.createDirectories(runs);
        Path candidate = runs.resolve(runId);
        command(repository, List.of("git", "worktree", "add", "--detach", candidate.toString(), baselineTag), Duration.ofSeconds(30));
        return candidate;
    }

    public void removeOwned(Path candidate) throws Exception {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!runs.equals(normalized.getParent()) || !normalized.getFileName().toString().matches("[a-f0-9-]{36}"))
            throw new SecurityException("Can only remove this factory's run workspace");
        command(repository, List.of("git", "worktree", "remove", "--force", normalized.toString()), Duration.ofSeconds(30));
    }

    public String resolveCommit(String baselineTag) throws Exception {
        if (baselineTag == null || !baselineTag.matches("[a-zA-Z0-9][a-zA-Z0-9._/-]{0,79}")) {
            throw new IllegalArgumentException("Invalid baseline");
        }
        return command(repository, List.of("git", "rev-parse", "--verify", "--end-of-options", baselineTag + "^{commit}"), Duration.ofSeconds(10)).trim();
    }

    public void apply(Path candidate, String patch, List<String> allowed) throws Exception {
        List<String> changed = validateScope(patch, allowed);
        Path file = Files.createTempFile("factory-patch-", ".diff");
        try {
            Files.writeString(file, patch);
            String actualPaths = command(candidate, List.of("git", "apply", "--numstat", "-z", file.toString()), Duration.ofSeconds(20));
            List<String> actual = java.util.Arrays.stream(actualPaths.split("\u0000")).filter(line -> !line.isBlank()).map(line -> {
                String[] fields = line.split("\\t", 3);
                if (fields.length != 3) throw new SecurityException("Unparseable patch path");
                return fields[2];
            }).toList();
            if (actual.isEmpty() || !changed.containsAll(actual) || !actual.containsAll(changed)) {
                throw new SecurityException("Patch paths do not match the reviewed diff headers");
            }
            command(candidate, List.of("git", "apply", "--check", "--whitespace=error", file.toString()), Duration.ofSeconds(20));
            command(candidate, List.of("git", "apply", "--whitespace=error", file.toString()), Duration.ofSeconds(20));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Check applicability without changing the candidate or its index. */
    public void checkApply(Path candidate, String patch, List<String> allowed) throws Exception {
        validateScope(patch, allowed);
        Path file = Files.createTempFile("factory-preflight-", ".diff");
        try {
            Files.writeString(file, patch);
            command(candidate, List.of("git", "apply", "--check", "--whitespace=error", file.toString()), Duration.ofSeconds(20));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    public List<String> validateScope(String patch, List<String> allowed) {
        if (patch.lines().anyMatch(line -> line.matches("(?:new file mode|old mode|new mode|deleted file mode|index [^ ]+) (?:120000|160000)"))) {
            throw new SecurityException("Symlinks and submodules are outside worker authority");
        }
        List<String> changed = new ArrayList<>();
        for (String line : patch.lines().toList()) {
            var match = DIFF.matcher(line);
            if (line.startsWith("rename ") || line.startsWith("copy ")) throw new SecurityException("Renames and copies are outside worker authority");
            if (line.startsWith("diff --git ") && !match.matches()) throw new SecurityException("Ambiguous or quoted diff path");
            if (match.matches()) {
                if (!match.group(1).equals(match.group(2))) throw new IllegalArgumentException("Renames are not supported");
                String path = match.group(1);
                if (!path.matches("[A-Za-z0-9_./-]+") || java.util.Arrays.stream(path.split("/")).anyMatch(part -> part.equalsIgnoreCase(".git")) || path.startsWith("/") || path.contains("..") || allowed.stream().noneMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"))) {
                    throw new SecurityException("Patch outside approved scope: " + path);
                }
                changed.add(path);
            }
        }
        if (changed.isEmpty()) throw new IllegalArgumentException("Patch has no recognized file changes");
        return changed;
    }

    public String diff(Path candidate) throws Exception {
        command(candidate, List.of("git", "add", "-N", "."), Duration.ofSeconds(20));
        return command(candidate, List.of("git", "diff", "--binary"), Duration.ofSeconds(20));
    }

    public void reset(Path candidate, String baselineTag) throws Exception {
        command(candidate, List.of("git", "reset", "--hard", baselineTag), Duration.ofSeconds(30));
        command(candidate, List.of("git", "clean", "-fd"), Duration.ofSeconds(30));
    }

    public static String command(Path directory, List<String> arguments, Duration timeout) throws Exception {
        return CommandRunner.checked(directory, arguments, timeout);
    }
}
