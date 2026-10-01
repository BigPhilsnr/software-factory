package dev.softwarefactory.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.softwarefactory.platform.Json;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class RunStateTest {
    @Test
    void deserializedStateUsesConcurrentMaps() throws Exception {
        var state = new RunState("00000000-0000-0000-0000-000000000001", "scenario", "hash");
        state.tasks.put("a", TaskStatus.DONE);
        state.attempts.put("a", 1);
        RunState restored = Json.MAPPER.readValue(Json.MAPPER.writeValueAsString(state), RunState.class);
        for (Map<?, ?> map : List.of(
                restored.tasks,
                restored.artifactHashes,
                restored.attempts,
                restored.diagnosticVersions,
                restored.artifactVersions,
                restored.approvals,
                restored.patchDrafts,
                restored.reviewFeedback)) {
            assertInstanceOf(ConcurrentHashMap.class, map);
        }
        assertEquals(TaskStatus.DONE, restored.tasks.get("a"));
    }

    @Test
    void theWireFormatContainsExactlyTheDocumentedFields() {
        JsonNode json = Json.MAPPER.valueToTree(new RunState("00000000-0000-0000-0000-000000000001", "s", "h"));
        var names = new java.util.TreeSet<String>();
        json.fieldNames().forEachRemaining(names::add);
        assertEquals(
                new java.util.TreeSet<>(List.of(
                        "id",
                        "revision",
                        "scenario",
                        "specPath",
                        "specHash",
                        "baselineTag",
                        "baselineCommit",
                        "candidatePath",
                        "mode",
                        "requirementHash",
                        "status",
                        "pendingApprovalTask",
                        "pendingApprovalHash",
                        "pendingClarificationTask",
                        "validatedCandidateHash",
                        "revisionRequiredTask",
                        "modelCalls",
                        "maxModelCalls",
                        "startedAt",
                        "finishedAt",
                        "tasks",
                        "artifactHashes",
                        "attempts",
                        "diagnosticVersions",
                        "artifactVersions",
                        "approvals",
                        "patchDrafts",
                        "reviewFeedback")),
                names);
    }

    @Test
    void invalidationClearsEveryInputDerivedRecordButKeepsEvidenceVersions() {
        var state = new RunState("00000000-0000-0000-0000-000000000001", "s", "h");
        state.artifactHashes.put("a", "hash");
        state.patchDrafts.put("a", 1);
        state.approvals.put("a", "approved");
        state.attempts.put("a", 1);
        state.artifactVersions.put("a", 3);
        state.diagnosticVersions.put("a", 2);
        state.invalidate("a", TaskStatus.STALE);
        assertEquals(TaskStatus.STALE, state.tasks.get("a"));
        assertTrue(state.artifactHashes.isEmpty() && state.patchDrafts.isEmpty());
        assertTrue(state.approvals.isEmpty() && state.attempts.isEmpty());
        assertEquals(3, state.artifactVersions.get("a"), "Evidence is immutable, so versions never restart");
        assertEquals(2, state.diagnosticVersions.get("a"));
    }

    @Test
    void waitingAndTerminalStatesAreDistinguished() {
        var state = new RunState("00000000-0000-0000-0000-000000000001", "s", "h");
        assertFalse(state.awaitsOperator());
        state.revisionRequiredTask = "validate";
        assertTrue(state.awaitsOperator());
        assertEquals(
                List.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.SAFE_STOPPED, RunStatus.NOT_APPROVED),
                java.util.Arrays.stream(RunStatus.values())
                        .filter(RunStatus::isTerminal)
                        .toList());
    }
}
