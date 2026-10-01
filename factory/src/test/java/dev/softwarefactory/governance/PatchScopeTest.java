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

    @Test
    void refusesAHunkWhoseDeclaredLineCountDoesNotMatchItsBody() {
        // git apply does not reject this outright: it silently stops reading the hunk at the declared
        // count and drops the rest. A model that miscounts must get a loud, specific, retryable error,
        // not a file quietly missing its last two lines.
        String tooFewDeclared = "diff --git a/shortener/X.java b/shortener/X.java\nnew file mode 100644\n"
                + "--- /dev/null\n+++ b/shortener/X.java\n@@ -0,0 +1,2 @@\n+class X {\n+    void m() {}\n+}\n";
        var error = assertThrows(
                IllegalArgumentException.class, () -> PatchScope.changedPaths(tooFewDeclared, List.of("shortener")));
        assertTrue(error.getMessage().contains("-0,+2"), error.getMessage());
        assertTrue(error.getMessage().contains("-0,+3"), error.getMessage());

        String tooManyDeclared = "diff --git a/shortener/X.java b/shortener/X.java\nnew file mode 100644\n"
                + "--- /dev/null\n+++ b/shortener/X.java\n@@ -0,0 +1,5 @@\n+class X {}\n";
        assertThrows(
                IllegalArgumentException.class, () -> PatchScope.changedPaths(tooManyDeclared, List.of("shortener")));
    }

    @Test
    void acceptsAccurateMultiHunkModificationsWithContextAndRemovals() {
        String patch = "diff --git a/shortener/X.java b/shortener/X.java\n--- a/shortener/X.java\n"
                + "+++ b/shortener/X.java\n@@ -1,3 +1,3 @@\n package shortener;\n-int a = 1;\n+int a = 2;\n"
                + " int b;\n@@ -10,2 +10,2 @@\n-int c = 1;\n+int c = 2;\n old();\n";
        assertTrue(PatchScope.changedPaths(patch, List.of("shortener")).contains("shortener/X.java"));
    }
}
