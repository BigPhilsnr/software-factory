package dev.softwarefactory.audit;

import dev.softwarefactory.governance.Hashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Evidence files are immutable once written for a run/task pair. */
public final class EvidenceStore {
    private static final Pattern RUN_ID = Pattern.compile("[a-f0-9-]{36}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,64}");
    private static final String SUFFIX = ".txt";

    private final Path root;

    public EvidenceStore(Path root) {
        this.root = root;
    }

    /**
     * @return the SHA-256 of the content
     * @throws IllegalStateException when different content already exists under this name
     */
    public String write(String runId, String name, String content) throws IOException {
        if (!RUN_ID.matcher(runId).matches() || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid evidence identity");
        }
        Path folder = root.resolve(runId);
        Files.createDirectories(folder);
        if (Files.isSymbolicLink(folder)) throw new SecurityException("Evidence directory cannot be a symlink");
        Path destination = folder.resolve(name + SUFFIX);
        Path temporary = Files.createTempFile(folder, "evidence-", ".tmp");
        try {
            Files.writeString(temporary, content);
            if (!publish(temporary, destination) && !holds(destination, content)) {
                throw new IllegalStateException("Conflicting immutable evidence: " + name);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return Hashes.sha256(content);
    }

    public Path path(String runId, String name) {
        return root.resolve(runId).resolve(name + SUFFIX);
    }

    /** Atomic create without replace: a concurrent writer cannot overwrite evidence. */
    private static boolean publish(Path temporary, Path destination) throws IOException {
        try {
            Files.createLink(destination, temporary);
            return true;
        } catch (FileAlreadyExistsException existing) {
            return false;
        }
    }

    private static boolean holds(Path destination, String content) throws IOException {
        return !Files.isSymbolicLink(destination)
                && Arrays.equals(Files.readAllBytes(destination), content.getBytes(StandardCharsets.UTF_8));
    }
}
