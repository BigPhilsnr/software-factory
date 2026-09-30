package dev.softwarefactory.governance;

import dev.softwarefactory.workflow.Stage;
import dev.softwarefactory.workflow.TaskKind;
import dev.softwarefactory.workflow.TaskSpec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatchPolicyTest {
    private TaskSpec task() {
        return new TaskSpec("fix", Stage.REPAIR, List.of(), TaskKind.PATCH, "implementer", "fix", null,
            List.of(), List.of("shortener"), false);
    }

    @Test
    void dependencyAndMigrationChangesRequireOperatorApproval() {
        assertTrue(PatchPolicy.requiresApproval(task(), "diff --git a/factory/src/Change.java b/factory/src/Change.java\n"));
        assertTrue(PatchPolicy.requiresApproval(task(), "diff --git a/orchestrator/src/Change.java b/orchestrator/src/Change.java\n"));
        assertTrue(PatchPolicy.requiresApproval(task(), "diff --git a/shortener/pom.xml b/shortener/pom.xml\n"));
        assertTrue(PatchPolicy.requiresApproval(task(), "diff --git a/shortener/src/main/resources/db/migration/V2.sql b/shortener/src/main/resources/db/migration/V2.sql\n"));
        assertFalse(PatchPolicy.requiresApproval(task(), "diff --git a/shortener/src/main/java/Fix.java b/shortener/src/main/java/Fix.java\n"));
    }
}
