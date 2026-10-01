package dev.softwarefactory.candidate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.softwarefactory.governance.PolicyViolationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitWorkspaceTest {
    @TempDir
    Path repository;

    private static String newFile(String path) {
        return "diff --git a/" + path + " b/" + path + "\nnew file mode 100644\n--- /dev/null\n+++ b/" + path
                + "\n@@ -0,0 +1 @@\n+content\n";
    }

    @Test
    void refusesBaselineReferencesThatLookLikeOptions() {
        assertThrows(IllegalArgumentException.class, () -> new GitWorkspace(repository).resolveCommit("--help"));
    }

    @Test
    void preflightRejectsCorruptHunksWithoutWritingFiles() {
        String patch =
                "diff --git a/new.txt b/new.txt\nnew file mode 100644\n--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1,3 @@\n+only one line\n";
        assertThrows(
                IOException.class,
                () -> new GitWorkspace(repository).checkApply(repository, patch, List.of("new.txt")));
        assertFalse(Files.exists(repository.resolve("new.txt")));
    }

    @Test
    void preflightAcceptsValidPatchWithoutApplyingIt() throws Exception {
        new GitWorkspace(repository).checkApply(repository, newFile("new.txt"), List.of("new.txt"));
        assertFalse(Files.exists(repository.resolve("new.txt")));
    }

    @Test
    void rejectsPatchOutsideDeclaredScopeBeforeApplyingIt() {
        assertThrows(
                PolicyViolationException.class,
                () -> new GitWorkspace(repository)
                        .apply(repository, newFile("control/secrets.txt"), List.of("shortener")));
    }

    @Test
    void releaseDiffIncludesIgnoredFilesButNotBuildOutput() throws Exception {
        String baseline = baseline();
        var workspace = new GitWorkspace(repository);
        Path candidate = workspace.create(UUID.randomUUID().toString(), "baseline");
        try {
            // Ignored by the baseline's .gitignore, e.g. written by a crafted patch before scope rules existed.
            Files.writeString(candidate.resolve("shortener/Hidden.secret"), "hidden change\n");
            Files.createDirectories(candidate.resolve("shortener/target/classes"));
            Files.writeString(candidate.resolve("shortener/target/classes/Built.class"), "build output\n");
            workspace.apply(candidate, newFile("shortener/Visible.java"), List.of("shortener"));
            String diff = workspace.diff(candidate, baseline);
            assertTrue(diff.contains("shortener/Visible.java"));
            assertTrue(diff.contains("shortener/Hidden.secret"), "Ignored files must be part of the reviewed evidence");
            assertFalse(diff.contains("Built.class"), "Validator build output is not a change");
            assertTrue(
                    CommandRunner.checked(
                                    candidate,
                                    List.of("git", "status", "--porcelain", "--untracked-files=no"),
                                    Duration.ofSeconds(5))
                            .isBlank(),
                    "The candidate's own index must stay untouched");

            workspace.reset(candidate, baseline);
            assertFalse(Files.exists(candidate.resolve("shortener/Hidden.secret")), "Reset removes ignored files");
            assertFalse(Files.exists(candidate.resolve("shortener/target")), "Reset removes stale build output");
            assertFalse(Files.exists(candidate.resolve("shortener/Visible.java")));
            assertTrue(workspace.diff(candidate, baseline).isEmpty());
        } finally {
            workspace.removeOwned(candidate);
        }
    }

    @Test
    void hostGitDoesNotRunRepositoryHooks() throws Exception {
        baseline();
        Path marker = repository.resolve("hook-ran");
        Path hook = repository.resolve(".git/hooks/post-checkout");
        Files.writeString(hook, "#!/bin/sh\ntouch '" + marker + "'\n");
        Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
        var workspace = new GitWorkspace(repository);
        Path candidate = workspace.create(UUID.randomUUID().toString(), "baseline");
        try {
            assertFalse(Files.exists(marker));
        } finally {
            workspace.removeOwned(candidate);
        }
    }

    @Test
    void removesOnlyItsOwnRunWorkspaces() {
        var workspace = new GitWorkspace(repository);
        assertThrows(PolicyViolationException.class, () -> workspace.removeOwned(repository.resolve("shortener")));
    }

    private String baseline() throws Exception {
        Files.createDirectories(repository.resolve("shortener"));
        Files.writeString(repository.resolve(".gitignore"), "**/target/\n*.secret\n");
        Files.writeString(repository.resolve("shortener/Existing.java"), "class Existing {}\n");
        git("init", "-q");
        git("add", ".");
        git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "baseline");
        git("tag", "baseline");
        return CommandRunner.checked(repository, List.of("git", "rev-parse", "HEAD"), Duration.ofSeconds(5))
                .strip();
    }

    private void git(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        CommandRunner.checked(repository, command, Duration.ofSeconds(10));
    }
}
