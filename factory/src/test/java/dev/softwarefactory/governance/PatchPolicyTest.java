package dev.softwarefactory.governance;


import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class PatchPolicyTest {
    @Test
    void dependencyAndMigrationChangesRequireOperatorApproval() {
        assertTrue(PatchPolicy.requiresApproval(false, "diff --git a/factory/src/Change.java b/factory/src/Change.java\n"));
        assertTrue(PatchPolicy.requiresApproval(false, "diff --git a/orchestrator/src/Change.java b/orchestrator/src/Change.java\n"));
        assertTrue(PatchPolicy.requiresApproval(false, "diff --git a/shortener/pom.xml b/shortener/pom.xml\n"));
        assertTrue(PatchPolicy.requiresApproval(false, "diff --git a/shortener/src/main/resources/db/migration/V2.sql b/shortener/src/main/resources/db/migration/V2.sql\n"));
        assertFalse(PatchPolicy.requiresApproval(false, "diff --git a/shortener/src/main/java/Fix.java b/shortener/src/main/java/Fix.java\n"));
    }
}
