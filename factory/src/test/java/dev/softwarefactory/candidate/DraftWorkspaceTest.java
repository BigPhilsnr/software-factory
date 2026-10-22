package dev.softwarefactory.candidate;

import static org.junit.jupiter.api.Assertions.*;

import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.governance.PolicyViolationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DraftWorkspaceTest {
    @TempDir
    Path root;

    @Test
    void failedCheckpointCannotPassFinalChecks() throws Exception {
        Path candidate = Files.createDirectory(root.resolve("candidate"));
        Files.writeString(candidate.resolve("README.md"), "original\n");
        var checkpoint = DraftWorkspace.create(candidate, root.resolve("draft"), "input");
        var draft = new DraftWorkspace(
                checkpoint,
                new GitWorkspace(root),
                List.of("README.md"),
                saved -> {
                    throw new java.io.IOException("storage unavailable");
                },
                (p, d) -> "passed");
        assertThrows(java.io.IOException.class, () -> draft.replace("README.md", "original", "updated"));
        assertThrows(PolicyViolationException.class, draft::check);
        assertEquals("original\n", Files.readString(candidate.resolve("README.md")));
    }

    @Test
    void editsAreAtomicScopedAndResumableWithoutTouchingCandidate() throws Exception {
        Path candidate = Files.createDirectory(root.resolve("candidate"));
        Files.writeString(candidate.resolve("README.md"), "original\n");
        var initial = DraftWorkspace.create(candidate, root.resolve("draft"), "input");
        var saved = new AtomicReference<>(initial);
        var git = new GitWorkspace(root);
        var draft = new DraftWorkspace(initial, git, List.of("README.md", "src"), saved::set, (p, d) -> "passed");
        draft.verify();
        assertThrows(IllegalArgumentException.class, () -> draft.write("README.md", "stale", "wrong"));
        Files.setPosixFilePermissions(
                draft.path().resolve("README.md"),
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        draft.replace("README.md", "original", "updated");
        assertTrue(Files.isExecutable(draft.path().resolve("README.md")));
        draft.write("src/New.java", "ABSENT", "class New {}\n");
        assertEquals("original\n", Files.readString(candidate.resolve("README.md")));
        assertFalse(Files.exists(candidate.resolve("src")));
        var resumed = new DraftWorkspace(saved.get(), git, List.of("README.md", "src"), saved::set, (p, d) -> "passed");
        resumed.verify();
        assertTrue(resumed.diff().contains("+updated"));
        assertTrue(resumed.diff().contains("+class New {}"));
        resumed.delete("src/New.java", Hashes.sha256("class New {}\n"));
        assertFalse(resumed.diff().contains("New.java"));
        assertThrows(IllegalArgumentException.class, () -> resumed.replace("README.md", "", "bad"));
    }

    @Test
    void deniesTraversalProtectedPathsSymlinksAndTamperedCheckpoints() throws Exception {
        Path candidate = Files.createDirectory(root.resolve("candidate"));
        Files.writeString(candidate.resolve("README.md"), "original\n");
        var initial = DraftWorkspace.create(candidate, root.resolve("draft"), "input");
        var draft = new DraftWorkspace(
                initial, new GitWorkspace(root), List.of("README.md", "src"), c -> {}, (p, d) -> "passed");
        for (String path : List.of(
                "../outside",
                "src/../../outside",
                "/tmp/outside",
                "src/.git/config",
                "src/.env",
                "src/credentials.json",
                "src/private_key.txt",
                "src/server.pem",
                "src/target/a",
                "src/a\ndiff --git a/x b/x")) {
            assertThrows(PolicyViolationException.class, () -> draft.write(path, "ABSENT", "bad"), path);
        }
        Files.createSymbolicLink(draft.path().resolve("src"), candidate);
        assertThrows(
                PolicyViolationException.class, () -> draft.write("src/README.md", Hashes.sha256("original\n"), "bad"));
        Files.delete(draft.path().resolve("src"));
        Files.writeString(draft.path().resolve("README.md"), "tampered");
        assertThrows(PolicyViolationException.class, draft::verify);
        assertEquals("original\n", Files.readString(candidate.resolve("README.md")));
    }
}
