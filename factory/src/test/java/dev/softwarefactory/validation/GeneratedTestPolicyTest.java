package dev.softwarefactory.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.softwarefactory.governance.PolicyViolationException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GeneratedTestPolicyTest {
    private static final String TEST = "shortener/src/test/java/dev/shortener/link/ExpiryTest.java";

    @Test
    void deletingAnExistingTestIsAPolicyViolation() {
        String deletion = "diff --git a/" + TEST + " b/" + TEST + "\ndeleted file mode 100644\n--- a/" + TEST
                + "\n+++ /dev/null\n@@ -1 +0,0 @@\n-class ExpiryTest {}\n";
        assertThrows(PolicyViolationException.class, () -> GeneratedTestPolicy.check(deletion));
    }

    @Test
    void addedTestsCannotOptOutOfTheDefaultSuite() {
        String tagged = "diff --git a/" + TEST + " b/" + TEST + "\nnew file mode 100644\n--- /dev/null\n+++ b/" + TEST
                + "\n@@ -0,0 +1,2 @@\n+@Tag( \"integration\" )\n+class ExpiryTest {}\n";
        assertThrows(PolicyViolationException.class, () -> GeneratedTestPolicy.check(tagged));
    }

    @Test
    void ordinaryTestChangesAreAllowedAndReportedAsRequiredClasses() {
        String added = "diff --git a/" + TEST + " b/" + TEST + "\nnew file mode 100644\n--- /dev/null\n+++ b/" + TEST
                + "\n@@ -0,0 +1 @@\n+class ExpiryTest { @Test void expires() {} }\n"
                + "diff --git a/shortener/src/main/java/dev/shortener/link/Link.java b/shortener/src/main/java/dev/shortener/link/Link.java\n";
        assertDoesNotThrow(() -> GeneratedTestPolicy.check(added));
        assertEquals(Set.of("dev.shortener.link.ExpiryTest"), GeneratedTestPolicy.changedTestClasses(added));
    }

    @Test
    void surefireNamingDecidesWhichChangedFilesAreTestClasses() {
        String patch = header("shortener/src/test/java/TestExpiry.java")
                + header("shortener/src/test/java/dev/shortener/link/ExpiryTests.java")
                + header("src/test/java/a/b/ExpiryTestCase.java")
                + header("shortener/src/test/java/dev/shortener/link/ExpiryFixture.java")
                + header("shortener/src/test/java/dev/1bad/ExpiryTest.java")
                + header("shortener/src/test/java//ExpiryTest.java")
                + header("shortener/notsrc/test/java/ExpiryTest.java")
                + header("shortener/src/test/java/dev/Test.kt");
        assertEquals(
                Set.of("TestExpiry", "dev.shortener.link.ExpiryTests", "a.b.ExpiryTestCase"),
                GeneratedTestPolicy.changedTestClasses(patch));
    }

    @Test
    void theLastTestRootInAPathDefinesThePackage() {
        assertEquals(
                Set.of("pkg.NestedTest"),
                GeneratedTestPolicy.changedTestClasses(
                        header("module/src/test/java/x/src/test/java/pkg/NestedTest.java")));
    }

    @Test
    void derivesTheRegressionTestClassFromThePatch() {
        String path = "shortener/src/test/java/dev/shortener/redirect/RedirectRegressionTest.java";
        String patch = header(path) + "--- /dev/null\n+++ b/" + path + "\n";
        assertEquals("RedirectRegressionTest", GeneratedTestPolicy.regressionTestClass(patch));
    }

    @Test
    void rejectsRegressionPatchesWithoutExactlyOneProductTestClass() {
        String two = "+++ b/shortener/src/test/java/AaaTest.java\n+++ b/shortener/src/test/java/BbbTest.java\n";
        assertThrows(IllegalArgumentException.class, () -> GeneratedTestPolicy.regressionTestClass(two));
        for (String none : new String[] {
            "+++ b/shortener/src/main/java/Link.java\n",
            "+++ b/other/src/test/java/AaaTest.java\n",
            "+++ b/shortener/src/test/java/Test.java\n",
            "+++ b/shortener/src/test/java/bad-dir/AaaTest.java\n",
            "+++ b/shortener/src/test/java/9Test.java\n",
            "+++ b/shortener/src/test/java/A_bTest.java\n"
        }) {
            assertThrows(IllegalArgumentException.class, () -> GeneratedTestPolicy.regressionTestClass(none), none);
        }
    }

    private static String header(String path) {
        return "diff --git a/" + path + " b/" + path + "\n";
    }
}
