package com.example.factory.infra;

import com.example.factory.domain.Hashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
        if (Files.exists(destination)) throw new IllegalStateException("Evidence already exists: " + taskId);
        Path temporary = Files.createTempFile(folder, taskId, ".tmp");
        try {
            Files.writeString(temporary, content);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return Hashes.sha256(content);
    }

    public Path path(String runId, String taskId) { return root.resolve(runId).resolve(taskId + ".txt"); }
}
