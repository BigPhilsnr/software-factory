package com.example.factory.infra;

import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitWorkspaceTest {
    @TempDir Path repository;

    @Test
    void rejectsPatchOutsideDeclaredScopeBeforeApplyingIt() {
        String patch = "diff --git a/control/secrets.txt b/control/secrets.txt\n" +
            "new file mode 100644\n--- /dev/null\n+++ b/control/secrets.txt\n@@ -0,0 +1 @@\n+stolen\n";
        GitWorkspace workspace = new GitWorkspace(repository);
        assertThrows(SecurityException.class, () -> workspace.apply(repository, patch, List.of("shortener")));
    }
}
