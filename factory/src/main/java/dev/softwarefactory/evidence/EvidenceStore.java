package dev.softwarefactory.evidence;

import dev.softwarefactory.governance.Hashes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;

/** Evidence files are immutable once written for a run/task pair. */
public final class EvidenceStore {
    private final Path root;

    public EvidenceStore(Path root) { this.root = root; }

    public String write(String runId, String taskId, String content) throws IOException {
        if (!runId.matches("[a-f0-9-]{36}") || !taskId.matches("[a-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("Invalid evidence identity");
        }
        Path folder = root.resolve(runId);
        Files.createDirectories(folder);
        Path destination = folder.resolve(taskId + ".txt");
        if (Files.isSymbolicLink(folder)) throw new SecurityException("Evidence directory cannot be a symlink");
        Path temporary = Files.createTempFile(folder, taskId, ".tmp");
        try {
            Files.writeString(temporary, content);
            try {
                // Atomic create without replace: a concurrent writer cannot overwrite evidence.
                Files.createLink(destination, temporary);
            } catch (FileAlreadyExistsException existing) {
                if (Files.isSymbolicLink(destination) || !java.util.Arrays.equals(Files.readAllBytes(destination),
                        content.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                    throw new IllegalStateException("Conflicting immutable evidence: " + taskId);
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return Hashes.sha256(content);
    }

    public Path path(String runId, String taskId) { return root.resolve(runId).resolve(taskId + ".txt"); }
}
