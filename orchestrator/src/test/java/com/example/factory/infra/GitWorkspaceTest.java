package com.example.factory.infra;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitWorkspaceTest {
    @TempDir Path repository;

    @Test
    void preflightRejectsCorruptHunksWithoutWritingFiles() {
        String patch = "diff --git a/new.txt b/new.txt\nnew file mode 100644\n--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1,3 @@\n+only one line\n";
        assertThrows(IOException.class, () -> new GitWorkspace(repository).checkApply(repository, patch, List.of("new.txt")));
        assertFalse(Files.exists(repository.resolve("new.txt")));
    }

    @Test
    void preflightAcceptsValidPatchWithoutApplyingIt() throws Exception {
        String patch = "diff --git a/new.txt b/new.txt\nnew file mode 100644\n--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1 @@\n+one line\n";
        new GitWorkspace(repository).checkApply(repository, patch, List.of("new.txt"));
        assertFalse(Files.exists(repository.resolve("new.txt")));
    }

    @Test
    void rejectsPatchOutsideDeclaredScopeBeforeApplyingIt() {
        String patch = "diff --git a/control/secrets.txt b/control/secrets.txt\n" +
            "new file mode 100644\n--- /dev/null\n+++ b/control/secrets.txt\n@@ -0,0 +1 @@\n+stolen\n";
        GitWorkspace workspace = new GitWorkspace(repository);
        assertThrows(SecurityException.class, () -> workspace.apply(repository, patch, List.of("shortener")));
    }
}
