package dev.softwarefactory.candidate;

import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.governance.PatchScope;
import dev.softwarefactory.governance.PolicyViolationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Compare-and-swap UTF-8 edits, confined to regular files in a task's write scope. */
final class DraftFiles {
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private final Path root;
    private final List<String> scope;

    DraftFiles(Path root, List<String> scope) {
        this.root = root;
        this.scope = List.copyOf(scope);
    }

    String write(String name, String expectedHash, String text) throws IOException {
        Path file = checked(name);
        if (text.indexOf('\0') >= 0 || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_FILE_BYTES)
            throw new IllegalArgumentException("Draft file must be UTF-8 text of at most 256 KiB");
        requireVersion(file, expectedHash);
        Path parent = java.util.Objects.requireNonNull(file.getParent(), "Draft file parent");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".draft-", ".tmp");
        try {
            Files.writeString(temporary, text);
            if (Files.exists(file)) Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(file));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return Hashes.sha256(text);
    }

    String read(String name) throws IOException {
        Path file = checked(name);
        if (!Files.exists(file)) return "ABSENT";
        requireSmall(file);
        String text = Files.readString(file);
        return "sha256=" + Hashes.sha256(text) + "\n" + text;
    }

    String replace(String name, String oldText, String newText) throws IOException {
        Path file = checked(name);
        requireSmall(file);
        String text = Files.readString(file);
        int offset = text.indexOf(oldText);
        if (oldText.isEmpty() || offset < 0 || text.indexOf(oldText, offset + 1) >= 0)
            throw new IllegalArgumentException("Replacement must match exactly one nonempty fragment");
        return write(
                name,
                Hashes.sha256(text),
                text.substring(0, offset) + newText + text.substring(offset + oldText.length()));
    }

    void delete(String name, String expectedHash) throws IOException {
        Path file = checked(name);
        requireVersion(file, expectedHash);
        Files.delete(file);
    }

    private static void requireVersion(Path file, String expectedHash) throws IOException {
        if (!Files.exists(file)) {
            if (!Hashes.same("ABSENT", expectedHash))
                throw new IllegalArgumentException("File is absent; expected hash must be ABSENT");
            return;
        }
        requireSmall(file);
        if (!Hashes.same(Hashes.sha256(Files.readAllBytes(file)), expectedHash))
            throw new IllegalArgumentException("File changed; read it again before editing");
    }

    private static void requireSmall(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES)
            throw new IllegalArgumentException("Draft path is not a regular file of at most 256 KiB");
    }

    private Path checked(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_./-]+")) throw new PolicyViolationException("Invalid draft path");
        denyCredentials(name);
        PatchScope.changedPaths("diff --git a/" + name + " b/" + name + "\n", scope);
        Path file = root.resolve(name).normalize();
        if (!file.startsWith(root) || file.equals(root))
            throw new PolicyViolationException("Draft path escaped workspace");
        requireNoLinks(root, file);
        return file;
    }

    private static void denyCredentials(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches(".*(credentials|secret|private[-_]?key).*")
                || lower.endsWith(".pem")
                || lower.endsWith(".key")) throw new PolicyViolationException("Credential paths are not draft inputs");
    }

    static void requireNoLinks(Path root, Path file) {
        Path cursor = file;
        while (cursor != null) {
            if (Files.isSymbolicLink(cursor))
                throw new PolicyViolationException("Draft paths cannot traverse symlinks");
            if (cursor.equals(root)) break;
            cursor = cursor.getParent();
        }
        if (!file.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
            throw new PolicyViolationException("Draft path escaped workspace");
    }
}
