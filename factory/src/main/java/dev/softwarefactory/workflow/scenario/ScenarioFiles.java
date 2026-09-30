package dev.softwarefactory.workflow.scenario;

import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves pre-organization scenario paths without rewriting persisted run hashes. */
public final class ScenarioFiles {
    private ScenarioFiles() {}

    public static Path resolve(Path requested) {
        Path path = requested.toAbsolutePath().normalize();
        if (Files.isRegularFile(path)) return path;
        Path parent = path.getParent();
        String name = path.getFileName().toString();
        if (parent != null && parent.getFileName().toString().equals("scenarios") && name.endsWith(".json")) {
            Path relocated = parent.resolve(name.substring(0, name.length() - 5)).resolve("scenario.json");
            if (Files.isRegularFile(relocated)) return relocated;
        }
        return path;
    }
}
