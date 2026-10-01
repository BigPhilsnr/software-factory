package dev.softwarefactory.governance;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PatchScopeTest {
    private static String newFile(String path) {
        return "diff --git a/" + path + " b/" + path + "\nnew file mode 100644\n--- /dev/null\n+++ b/" + path
                + "\n@@ -0,0 +1 @@\n+content\n";
    }

    @Test
    void refusesSymlinksEvenInsideApprovedScope() {
        assertThrows(
                PolicyViolationException.class,
                () -> PatchScope.changedPaths(
                        "diff --git a/shortener/Secret.java b/shortener/Secret.java\nnew file mode 120000\n",
                        List.of("shortener")));
    }

    @Test
    void refusesGitMetadataAmbiguousHeadersAndRenames() {
        for (String path : List.of("shortener/.git/config", "shortener/a b.java", "shortener/a\\b.java")) {
            assertThrows(
                    PolicyViolationException.class,
                    () -> PatchScope.changedPaths("diff --git a/" + path + " b/" + path + "\n", List.of("shortener")),
                    path);
        }
        assertThrows(
                PolicyViolationException.class,
                () -> PatchScope.changedPaths(
                        "diff --git a/shortener/a b/shortener/a\nrename from a\nrename to b\n", List.of("shortener")));
        assertThrows(
                PolicyViolationException.class,
                () -> PatchScope.changedPaths(
                        "diff --git a/shortener/a.java b/shortener/b.java\n", List.of("shortener")));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "shortener/src/main/java/dev/target/Evil.java",
                "shortener/target/classes/Evil.class",
                "shortener/src/main/java/TARGET/Evil.java",
                "shortener/.gitattributes",
                "shortener/src/.gitmodules",
                "shortener/.gitkeep",
                "shortener/.env",
                "shortener/config/.env.local",
                "shortener/logs/server.log"
            })
    void refusesPathsThatGitMayIgnoreOrThatChangeHowChangesAreRecorded(String path) {
        assertThrows(
                PolicyViolationException.class,
                () -> PatchScope.changedPaths(newFile(path), List.of("shortener")),
                path);
    }

    @Test
    void allowsIgnoreFilesBecauseEvidenceDisablesIgnoreRules() {
        assertTrue(PatchScope.changedPaths(newFile("shortener/.gitignore"), List.of("shortener"))
                .contains("shortener/.gitignore"));
    }

    @Test
    void refusesPathsOutsideTheWriteScopeAndPatchesWithoutChanges() {
        assertThrows(
                PolicyViolationException.class,
                () -> PatchScope.changedPaths(newFile("control/secrets.txt"), List.of("shortener")));
        assertThrows(
                PolicyViolationException.class,
                () -> PatchScope.changedPaths(newFile("shortener/../control/x.txt"), List.of("shortener")));
        assertThrows(
                IllegalArgumentException.class, () -> PatchScope.changedPaths("not a diff\n", List.of("shortener")));
    }
}
