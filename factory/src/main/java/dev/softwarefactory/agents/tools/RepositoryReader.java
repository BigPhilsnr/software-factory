package dev.softwarefactory.agents.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Read-only, bounded access to source and documentation in exactly one checkout. */
public final class RepositoryReader {
    private static final int MAX_FILES = 2000;
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final List<String> ROOTS = List.of("factory/src", "shortener/src", "docs", "scripts", "scenarios");
    private static final List<String> FILES = List.of("README.md", "pom.xml", "factory/pom.xml", "factory/README.md",
        "shortener/pom.xml", "shortener/README.md", "shortener/openapi.yaml");
    private final Path root;

    public RepositoryReader(Path root) { this.root = root.toAbsolutePath().normalize(); }

    boolean allowed(Path file) {
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        String name = relative.toString().replace('\\', '/');
        for (Path part : relative) {
            String segment = part.toString().toLowerCase(java.util.Locale.ROOT);
            if (segment.startsWith(".") || List.of("target", "node_modules", "credentials", "secrets").contains(segment)) return false;
        }
        if (FILES.contains(name)) return true;
        return ROOTS.stream().anyMatch(prefix -> name.startsWith(prefix + "/"))
            && name.matches(".*\\.(java|sql|md|xml|yaml|yml|json|py|js|css|html|patch|txt)$")
            && !name.toLowerCase(java.util.Locale.ROOT).matches(".*(credentials|secret|private[-_]?key).*" );
    }

    private Path checked(String name) throws Exception {
        Path path = root.resolve(name).normalize();
        if (!path.startsWith(root) || !allowed(path)) throw new SecurityException("Path is outside the source allowlist");
        Path cursor = root;
        for (Path part : root.relativize(path)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new SecurityException("Symbolic links are not source inputs");
        }
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) throw new IllegalArgumentException("File unavailable or too large");
        return path;
    }

    private List<Path> files() throws Exception {
        List<Path> result = new ArrayList<>();
        for (String name : FILES) if (Files.isRegularFile(root.resolve(name))) result.add(root.resolve(name));
        for (String name : ROOTS) {
            Path directory = root.resolve(name);
            if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) continue;
            try (var paths = Files.walk(directory, 24)) {
                for (Path path : paths.filter(p -> Files.isRegularFile(p) && !Files.isSymbolicLink(p) && allowed(p)).limit(MAX_FILES).sorted().toList()) {
                    if (result.size() >= MAX_FILES) return result;
                    result.add(path);
                }
            }
        }
        return result;
    }

    public String list(String prefix) throws Exception {
        if (prefix.length() > 200) throw new IllegalArgumentException("Prefix too long");
        StringBuilder output = new StringBuilder("Source paths (bounded to 200 matches):\n");
        int count = 0;
        for (Path path : files()) {
            String name = root.relativize(path).toString();
            if (name.startsWith(prefix)) {
                output.append(name).append('\n');
                if (++count >= 200) break;
            }
        }
        return output.toString();
    }

    public String read(String name, int startLine, int lineCount) throws Exception {
        if (startLine < 1 || lineCount < 1 || lineCount > 200) throw new IllegalArgumentException("Use 1-based lines and at most 200 lines");
        List<String> lines = Files.readAllLines(checked(name), StandardCharsets.UTF_8);
        StringBuilder output = new StringBuilder(name + " (" + lines.size() + " lines)\n");
        for (int i = startLine - 1; i < lines.size() && i - (startLine - 1) < lineCount && output.length() < 14000; i++) {
            output.append(i + 1).append(": ").append(lines.get(i)).append('\n');
        }
        return bounded(output.toString());
    }

    public String search(String text) throws Exception {
        if (text.isBlank() || text.length() > 200) throw new IllegalArgumentException("Search for 1..200 literal characters");
        StringBuilder output = new StringBuilder("Literal matches (at most 80):\n");
        int count = 0;
        for (Path path : files()) {
            List<String> lines;
            try { lines = Files.readAllLines(checked(root.relativize(path).toString()), StandardCharsets.UTF_8); }
            catch (java.io.IOException | IllegalArgumentException | SecurityException ignored) { continue; }
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(text)) {
                    output.append(root.relativize(path)).append(':').append(i + 1).append(':')
                        .append(lines.get(i), 0, Math.min(lines.get(i).length(), 400)).append('\n');
                    if (++count >= 80 || output.length() >= 14000) return bounded(output.toString());
                }
            }
        }
        return output.toString();
    }

    public String git(String operation) throws Exception {
        List<String> command = new ArrayList<>();
        switch (operation) {
            case "status" -> command.addAll(List.of("status", "--short", "--untracked-files=no"));
            case "diff" -> command.addAll(List.of("diff", "--no-ext-diff", "--no-textconv", "HEAD"));
            case "log" -> command.addAll(List.of("log", "-5", "--format=%h %s", "--no-show-signature"));
            default -> throw new IllegalArgumentException("Choose status, diff or log");
        }
        command.add("--");
        // Use the index so deleted source files are still visible. Drop a possibly truncated final name.
        List<String> listing = new ArrayList<>(List.of("ls-files", "-z", "--"));
        listing.addAll(ROOTS);
        listing.addAll(FILES);
        String[] tracked = executeGit(listing).split("\u0000", -1);
        for (int i = 0; i < tracked.length - 1 && i < MAX_FILES; i++) {
            Path file = root.resolve(tracked[i]).normalize();
            if (file.startsWith(root) && allowed(file) && !Files.isSymbolicLink(file)) command.add(tracked[i]);
        }
        if (command.getLast().equals("--")) return "No allowlisted source files";
        return executeGit(command);
    }

    private String executeGit(List<String> arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--no-pager", "--literal-pathspecs", "-c", "core.fsmonitor=false"));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process process = builder.start();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> process.getInputStream().readNBytes(16000));
            try {
                byte[] bytes = result.get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
                if (bytes.length == 16000) return new String(bytes, StandardCharsets.UTF_8) + "\n[truncated]";
                if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IllegalStateException("Git inspection failed");
                return new String(bytes, StandardCharsets.UTF_8);
            } finally { process.destroyForcibly(); }
        }
    }

    private static String bounded(String value) { return value.substring(0, Math.min(value.length(), 15000)); }
}
