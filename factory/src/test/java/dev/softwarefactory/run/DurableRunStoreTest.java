package dev.softwarefactory.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.softwarefactory.audit.AuditTrail;
import dev.softwarefactory.audit.RunJournal;
import dev.softwarefactory.audit.RunLease;
import dev.softwarefactory.audit.RunLeases;
import dev.softwarefactory.platform.Json;
import dev.softwarefactory.platform.WorkflowConflictException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DurableRunStoreTest {
    private static final String ID = "00000000-0000-0000-0000-000000000042";

    private final RunJournal journal = mock(RunJournal.class);
    private final AuditTrail trail = mock(AuditTrail.class);
    private final RunLeases leases = mock(RunLeases.class);
    private final DurableRunStore store = new DurableRunStore(journal, trail, leases);

    @Test
    void recordingWritesTheNextRevisionAndOnlyThenAdvancesTheInMemoryState() throws Exception {
        var state = new RunState(ID, "scenario", "hash");
        state.revision = 4;
        store.record(state, "TASK_DONE", "plan:abc");
        var json = ArgumentCaptor.forClass(String.class);
        verify(journal).record(eq(UUID.fromString(ID)), eq(4L), json.capture(), eq("TASK_DONE"), eq("plan:abc"));
        assertEquals(5, Json.MAPPER.readTree(json.getValue()).get("revision").asLong());
        assertEquals(5, state.revision);

        doThrow(new WorkflowConflictException("Stale run revision; reload before retrying"))
                .when(journal)
                .record(any(), anyLong(), any(), any(), any());
        assertThrows(WorkflowConflictException.class, () -> store.record(state, "TASK_DONE", "again"));
        assertEquals(5, state.revision, "A refused write leaves the revision untouched");
    }

    @Test
    void loadingRestoresTheStoredRevisionAndUnknownRunsAreMissing() throws Exception {
        String json = Json.MAPPER.writeValueAsString(new RunState(ID, "scenario", "hash"));
        when(journal.find(UUID.fromString(ID))).thenReturn(Optional.of(new RunJournal.Snapshot(json, 7)));
        RunState loaded = store.load(ID);
        assertEquals(7, loaded.revision);
        assertEquals("scenario", loaded.scenario);

        String unknown = "00000000-0000-0000-0000-000000000043";
        when(journal.find(UUID.fromString(unknown))).thenReturn(Optional.empty());
        var missing = assertThrows(RunStore.MissingRunException.class, () -> store.load(unknown));
        assertEquals("Run not found: " + unknown, missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> store.load("not-a-uuid"));
    }

    @Test
    void listingsParseSnapshotsAndAuditAndLeasesAreDelegated() throws Exception {
        String json = Json.MAPPER.writeValueAsString(new RunState(ID, "scenario", "hash"));
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        when(journal.recentStates()).thenReturn(List.of(json, json));
        when(journal.statesUpdatedBefore(cutoff)).thenReturn(List.of(json));
        when(trail.verify(ID)).thenReturn(true);
        RunLease lease = mock(RunLease.class);
        when(leases.acquire(ID)).thenReturn(lease);

        assertEquals(2, store.recentRuns().size());
        assertEquals(ID, store.runsUpdatedBefore(cutoff).getFirst().id);
        assertTrue(store.auditValid(ID));
        try (var held = store.lease(ID)) {
            assertTrue(held != null);
        }
        verify(lease).close();
    }
}
