package dev.softwarefactory.governance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.softwarefactory.execution.PolicyViolationException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TestChangePolicyTest {
    private static final String TEST = "shortener/src/test/java/dev/shortener/links/ExpiryTest.java";

    @Test
    void deletingAnExistingTestIsAPolicyViolation() {
        String deletion = "diff --git a/" + TEST + " b/" + TEST + "\ndeleted file mode 100644\n--- a/" + TEST
                + "\n+++ /dev/null\n@@ -1 +0,0 @@\n-class ExpiryTest {}\n";
        assertThrows(PolicyViolationException.class, () -> TestChangePolicy.check(deletion));
    }

    @Test
    void addedTestsCannotOptOutOfTheDefaultSuite() {
        String tagged = "diff --git a/" + TEST + " b/" + TEST + "\nnew file mode 100644\n--- /dev/null\n+++ b/" + TEST
                + "\n@@ -0,0 +1,2 @@\n+@Tag( \"integration\" )\n+class ExpiryTest {}\n";
        assertThrows(PolicyViolationException.class, () -> TestChangePolicy.check(tagged));
    }

    @Test
    void ordinaryTestChangesAreAllowedAndReportedAsRequiredClasses() {
        String added = "diff --git a/" + TEST + " b/" + TEST + "\nnew file mode 100644\n--- /dev/null\n+++ b/" + TEST
                + "\n@@ -0,0 +1 @@\n+class ExpiryTest { @Test void expires() {} }\n"
                + "diff --git a/shortener/src/main/java/dev/shortener/links/Link.java b/shortener/src/main/java/dev/shortener/links/Link.java\n";
        assertDoesNotThrow(() -> TestChangePolicy.check(added));
        assertEquals(Set.of("dev.shortener.links.ExpiryTest"), TestChangePolicy.testClasses(added));
    }
}
