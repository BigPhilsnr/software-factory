package dev.softwarefactory.agents;

import java.nio.file.Files;
import java.nio.file.Path;

/** Bounded product-only context; unrelated factory sources cannot crowd out the candidate. */
public final class SourceContext {
    private SourceContext() {}

    public static String read(Path candidate) throws Exception {
        Path root = candidate.toRealPath();
        StringBuilder result = new StringBuilder();
        append(result, root, root.resolve("pom.xml"));
        Path product = root.resolve("shortener");
        if (!Files.isDirectory(product) || Files.isSymbolicLink(product)) return result.toString();
        try (var files = Files.walk(product)) {
            for (Path file : files.sorted().toList()) {
                String name = root.relativize(file).toString();
                if (name.contains("/target/") || !(name.endsWith(".java") || name.endsWith(".xml")
                    || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".sql"))) continue;
                append(result, root, file);
            }
        }
        return result.toString();
    }

    private static void append(StringBuilder result, Path root, Path file) throws Exception {
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || !file.toRealPath().startsWith(root)) return;
        long size = Files.size(file);
        if (size > 16000 || result.length() + size > 80000) return;
        result.append("\n--- ").append(root.relativize(file)).append(" ---\n").append(Files.readString(file));
    }
}
