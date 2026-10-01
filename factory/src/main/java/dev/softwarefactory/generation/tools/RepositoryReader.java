package dev.softwarefactory.generation.tools;

import dev.softwarefactory.candidate.CommandRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Read-only, bounded access to source and documentation in exactly one checkout. */
public final class RepositoryReader {
    private static final int MAX_FILES = 2000;
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_LISTED = 200;
    private static final int MAX_LINES = 200;
    private static final int MAX_MATCHES = 80;
    private static final int MAX_EXCERPT = 400;
    private static final int MAX_QUERY = 200;
    private static final int MAX_OUTPUT = 14_000;
    private static final int MAX_RESULT = 15_000;
    private static final int MAX_GIT_OUTPUT = 16_000;
    private static final int MAX_WALK_DEPTH = 24;
    private static final String END_OF_OPTIONS = "--";
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(5);
    private static final List<String> ROOTS = List.of("factory/src", "shortener/src", "docs", "scripts", "scenarios");
    private static final List<String> FILES = List.of(
            "README.md",
            "pom.xml",
            "factory/pom.xml",
            "factory/README.md",
            "shortener/pom.xml",
            "shortener/README.md",
            "shortener/openapi.yaml");
    private static final Set<String> DENIED_SEGMENTS = Set.of("target", "node_modules", "credentials", "secrets");
    private static final Pattern SOURCE_EXTENSION =
            Pattern.compile(".*\\.(java|sql|md|xml|yaml|yml|json|py|js|css|html|patch|txt)$");
    private static final Pattern SECRET_NAME = Pattern.compile(".*(credentials|secret|private[-_]?key).*");
    /** Only these variables reach Git: no user or system configuration, credentials or pagers. */
    private static final Map<String, String> GIT_ENVIRONMENT = Map.of(
            "PATH", "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin",
            "GIT_OPTIONAL_LOCKS", "0",
            "GIT_CONFIG_NOSYSTEM", "1",
            "GIT_CONFIG_GLOBAL", "/dev/null",
            "GIT_TERMINAL_PROMPT", "0");

    private final Path root;

    public RepositoryReader(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    boolean allowed(Path file) {
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        String name = relative.toString().replace('\\', '/');
        for (Path part : relative) {
            String segment = part.toString().toLowerCase(Locale.ROOT);
            if (segment.startsWith(".") || DENIED_SEGMENTS.contains(segment)) return false;
        }
        return FILES.contains(name)
                || ROOTS.stream().anyMatch(prefix -> name.startsWith(prefix + "/"))
                        && SOURCE_EXTENSION.matcher(name).matches()
                        && !SECRET_NAME.matcher(name.toLowerCase(Locale.ROOT)).matches();
    }

    private Path checked(String name) throws IOException {
        Path path = root.resolve(name).normalize();
        if (!path.startsWith(root) || !allowed(path))
            throw new SecurityException("Path is outside the source allowlist");
        requireNoSymbolicLinks(path);
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES)
            throw new IllegalArgumentException("File unavailable or too large");
        return path;
    }

    private void requireNoSymbolicLinks(Path path) {
        Path cursor = root;
        for (Path part : root.relativize(path)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new SecurityException("Symbolic links are not source inputs");
        }
    }

    private List<Path> files() throws IOException {
        List<Path> result = new ArrayList<>();
        for (String name : FILES) if (Files.isRegularFile(root.resolve(name))) result.add(root.resolve(name));
        for (String name : ROOTS) {
            Path directory = root.resolve(name);
            if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) continue;
            try (var paths = Files.walk(directory, MAX_WALK_DEPTH)) {
                // Sort before limiting so the bounded selection is deterministic.
                for (Path path : paths.filter(p -> Files.isRegularFile(p) && !Files.isSymbolicLink(p) && allowed(p))
                        .sorted()
                        .limit(MAX_FILES)
                        .toList()) {
                    if (result.size() >= MAX_FILES) return result;
                    result.add(path);
                }
            }
        }
        return result;
    }

    public String list(String prefix) throws IOException {
        if (prefix.length() > MAX_QUERY) throw new IllegalArgumentException("Prefix too long");
        StringBuilder output = new StringBuilder("Source paths (bounded to " + MAX_LISTED + " matches):\n");
        int count = 0;
        for (Path path : files()) {
            String name = root.relativize(path).toString();
            if (name.startsWith(prefix)) {
                output.append(name).append('\n');
                count++;
                if (count >= MAX_LISTED) break;
            }
        }
        return output.toString();
    }

    public String read(String name, int startLine, int lineCount) throws IOException {
        if (startLine < 1 || lineCount < 1 || lineCount > MAX_LINES) {
            throw new IllegalArgumentException("Use 1-based lines and at most " + MAX_LINES + " lines");
        }
        List<String> lines = Files.readAllLines(checked(name), StandardCharsets.UTF_8);
        StringBuilder output = new StringBuilder(name + " (" + lines.size() + " lines)\n");
        for (int i = startLine - 1;
                i < lines.size() && i - (startLine - 1) < lineCount && output.length() < MAX_OUTPUT;
                i++) {
            output.append(i + 1).append(": ").append(lines.get(i)).append('\n');
        }
        return bounded(output.toString());
    }

    public String search(String text) throws IOException {
        if (text.isBlank() || text.length() > MAX_QUERY)
            throw new IllegalArgumentException("Search for 1.." + MAX_QUERY + " literal characters");
        StringBuilder output = new StringBuilder("Literal matches (at most " + MAX_MATCHES + "):\n");
        int count = 0;
        for (Path path : files()) {
            count += collectMatches(output, path, text, MAX_MATCHES - count);
            if (count >= MAX_MATCHES || output.length() >= MAX_OUTPUT) return bounded(output.toString());
        }
        return output.toString();
    }

    /**
     * Appends the lines of one file that contain {@code text}, stopping at {@code limit} matches or a full
     * output. Unreadable files are skipped: a search reports what it could read.
     *
     * @return the number of matches appended
     */
    private int collectMatches(StringBuilder output, Path path, String text, int limit) {
        List<String> lines;
        try {
            lines = Files.readAllLines(checked(root.relativize(path).toString()), StandardCharsets.UTF_8);
        } catch (IOException | IllegalArgumentException | SecurityException unreadable) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < lines.size() && count < limit && output.length() < MAX_OUTPUT; i++) {
            if (lines.get(i).contains(text)) {
                output.append(root.relativize(path))
                        .append(':')
                        .append(i + 1)
                        .append(':')
                        .append(lines.get(i), 0, Math.min(lines.get(i).length(), MAX_EXCERPT))
                        .append('\n');
                count++;
            }
        }
        return count;
    }

    public String git(String operation) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(
                switch (operation) {
                    case "status" -> List.of("status", "--short", "--untracked-files=no");
                    case "diff" -> List.of("diff", "--no-ext-diff", "--no-textconv", "HEAD");
                    case "log" -> List.of("log", "-5", "--format=%h %s", "--no-show-signature");
                    default -> throw new IllegalArgumentException("Choose status, diff or log");
                });
        command.add(END_OF_OPTIONS);
        List<String> sources = trackedSources();
        if (sources.isEmpty()) return "No allowlisted source files";
        command.addAll(sources);
        CommandRunner.Result result = executeGit(command);
        return result.truncated() ? result.output() + "\n[truncated]" : result.output();
    }

    /** Allowlisted files from the index, so deleted source files are still visible. */
    private List<String> trackedSources() throws IOException, InterruptedException {
        List<String> listing = new ArrayList<>(List.of("ls-files", "-z", END_OF_OPTIONS));
        listing.addAll(ROOTS);
        listing.addAll(FILES);
        // The final element is empty, or a name truncated by the output limit: drop it.
        String[] tracked = executeGit(listing).output().split("\u0000", -1);
        List<String> sources = new ArrayList<>();
        for (int i = 0; i < tracked.length - 1 && i < MAX_FILES; i++) {
            Path file = root.resolve(tracked[i]).normalize();
            if (file.startsWith(root) && allowed(file) && !Files.isSymbolicLink(file)) sources.add(tracked[i]);
        }
        return sources;
    }

    private CommandRunner.Result executeGit(List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                "git",
                "--no-pager",
                "--literal-pathspecs",
                "-c",
                "core.fsmonitor=false",
                "-c",
                "core.hooksPath=/dev/null"));
        command.addAll(arguments);
        CommandRunner.Result result = CommandRunner.run(CommandRunner.Invocation.of(root, command, GIT_TIMEOUT)
                .withIsolatedEnvironment(GIT_ENVIRONMENT)
                .withOutputLimit(MAX_GIT_OUTPUT, CommandRunner.Overflow.TRUNCATE));
        if (!result.truncated() && result.exitCode() != 0) throw new IllegalStateException("Git inspection failed");
        return result;
    }

    private static String bounded(String value) {
        return value.substring(0, Math.min(value.length(), MAX_RESULT));
    }
}
