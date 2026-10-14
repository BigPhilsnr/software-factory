package dev.softwarefactory.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PatchHunksTest {
    @Test
    void correctsOnlyCountsAcrossFilesAndHunks() {
        String body = "diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -3,9 +3,8 @@ method\n context\n-old\n+new\n+extra\n"
                + "@@ -20,3 +21,7 @@\n-last\n\\ No newline at end of file\n+replacement\n"
                + "diff --git a/b b/b\nnew file mode 100644\n--- /dev/null\n+++ b/b\n@@ -0,0 +1,99 @@\n+first\n+second\n";
        String expected = body.replace("-3,9 +3,8", "-3,2 +3,3")
                .replace("-20,3 +21,7", "-20,1 +21,1")
                .replace("+1,99", "+1,2");
        assertEquals(expected, PatchHunks.recount(body));
        assertEquals(expected, PatchHunks.recount(expected));
    }

    @Test
    void preservesValidOptionalCountsAndDeletion() {
        String patch = "--- a/a\n+++ b/a\n@@ -1 +1 @@\n-old\n+new\n" + "--- a/b\n+++ /dev/null\n@@ -1 +0,0 @@\n-gone\n";
        assertEquals(patch, PatchHunks.recount(patch));
    }
}
