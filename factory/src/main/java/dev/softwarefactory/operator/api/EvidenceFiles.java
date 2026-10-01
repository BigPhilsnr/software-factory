package dev.softwarefactory.operator.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Read-only operator access to a run's evidence files, by validated run ID and artifact name only. */
final class EvidenceFiles {
    private static final Pattern ARTIFACT_NAME = Pattern.compile("[a-z0-9-]+\\.txt");

    private static final String INVALID_NAME = "Invalid artifact name";

    private final Path root;

    EvidenceFiles(Path evidenceRoot) {
        this.root = evidenceRoot;
    }

    List<String> names(String runId) throws IOException {
        Path folder = root.resolve(runId);
        if (!Files.isDirectory(folder)) return List.of();
        try (var files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .filter(Objects::nonNull)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }
    }

    /**
     * @throws IllegalArgumentException for a run ID or name that could address anything but an evidence file
     * @throws NotFoundException when the artifact does not exist as a regular file
     */
    String read(String runId, String name) throws IOException {
        Path file = locate(runId, name);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file))
            throw new NotFoundException("Artifact not found: " + name);
        return Files.readString(file);
    }

    /** The only place a request becomes a path: a canonical UUID folder and a plain file name inside it. */
    private Path locate(String runId, String name) {
        String run = UUID.fromString(runId).toString();
        if (name == null || !ARTIFACT_NAME.matcher(name).matches()) throw new IllegalArgumentException(INVALID_NAME);
        Path folder = root.resolve(run).normalize();
        Path file = folder.resolve(name).normalize();
        if (!folder.startsWith(root) || !file.startsWith(folder)) throw new IllegalArgumentException(INVALID_NAME);
        return file;
    }

    /** For display only: missing or unreadable evidence is described instead of failing the whole view. */
    String display(String runId, String name) {
        try {
            return read(runId, name);
        } catch (IOException | NotFoundException | IllegalArgumentException unavailable) {
            return "Evidence unavailable: " + name
                    + ". Approval still requires intact evidence. You can reject this run.";
        }
    }
}
