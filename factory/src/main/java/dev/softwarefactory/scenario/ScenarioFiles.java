package dev.softwarefactory.scenario;

import dev.softwarefactory.platform.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads scenario documents and resolves pre-organization scenario paths without rewriting persisted run hashes. */
public final class ScenarioFiles {
    private static final String LEGACY_FOLDER = "scenarios";
    private static final String JSON_SUFFIX = ".json";

    private ScenarioFiles() {}

    /** A scenario as parsed, together with the exact text that run hashes are computed from. */
    public record Document(ScenarioSpec spec, String text) {}

    public static Document read(Path requested) throws IOException {
        String text = Files.readString(resolve(requested));
        return new Document(Json.MAPPER.readValue(text, ScenarioSpec.class), text);
    }

    public static Path resolve(Path requested) {
        Path path = requested.toAbsolutePath().normalize();
        if (Files.isRegularFile(path)) return path;
        Path parent = path.getParent();
        Path fileName = path.getFileName();
        Path folder = parent == null ? null : parent.getFileName();
        if (fileName == null || folder == null) return path;
        String name = fileName.toString();
        if (LEGACY_FOLDER.equals(folder.toString()) && name.endsWith(JSON_SUFFIX)) {
            Path relocated = parent.resolve(name.substring(0, name.length() - JSON_SUFFIX.length()))
                    .resolve("scenario.json");
            if (Files.isRegularFile(relocated)) return relocated;
        }
        return path;
    }
}
