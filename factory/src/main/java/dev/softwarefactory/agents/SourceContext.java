package dev.softwarefactory.agents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Bounded product-only context; unrelated factory sources cannot crowd out the candidate. */
public final class SourceContext {
    static final int MAX_FILE_BYTES = 16_000;
    static final int MAX_CONTEXT_BYTES = 80_000;
    private static final Set<String> EXTENSIONS = Set.of(".java", ".xml", ".yml", ".yaml", ".sql");

    private SourceContext() {}

    public static String read(Path candidate) throws IOException {
        Path root = candidate.toRealPath();
        StringBuilder result = new StringBuilder();
        List<String> omitted = new ArrayList<>();
        append(result, omitted, root, root.resolve("pom.xml"));
        Path product = root.resolve("shortener");
        if (Files.isDirectory(product) && !Files.isSymbolicLink(product)) {
            try (var files = Files.walk(product)) {
                for (Path file : files.sorted().toList()) {
                    String name = root.relativize(file).toString();
                    if (name.contains("/target/") || EXTENSIONS.stream().noneMatch(name::endsWith)) continue;
                    append(result, omitted, root, file);
                }
            }
        }
        if (!omitted.isEmpty()) {
            // Tell the model what it has not seen, so it reads those files with tools instead of guessing.
            result.append("\n--- OMITTED (size/budget): ").append(String.join(", ", omitted)).append(" ---\n");
        }
        return result.toString();
    }

    private static void append(StringBuilder result, List<String> omitted, Path root, Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || !file.toRealPath().startsWith(root)) return;
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES || result.length() + size > MAX_CONTEXT_BYTES) {
            omitted.add(root.relativize(file).toString());
            return;
        }
        result.append("\n--- ").append(root.relativize(file)).append(" ---\n").append(Files.readString(file));
    }
}
