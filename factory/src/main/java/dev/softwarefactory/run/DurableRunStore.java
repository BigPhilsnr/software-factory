package dev.softwarefactory.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.softwarefactory.audit.AuditTrail;
import dev.softwarefactory.audit.RunJournal;
import dev.softwarefactory.audit.RunLease;
import dev.softwarefactory.audit.RunLeases;
import dev.softwarefactory.platform.Json;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The production {@link RunStore}: run state as JSON snapshots in the control database's audited journal. */
public final class DurableRunStore implements RunStore {
    private final RunJournal journal;
    private final AuditTrail trail;
    private final RunLeases leases;

    public DurableRunStore(RunJournal journal, AuditTrail trail, RunLeases leases) {
        this.journal = journal;
        this.trail = trail;
        this.leases = leases;
    }

    @Override
    public RunState load(String id) throws IOException {
        RunJournal.Snapshot snapshot =
                journal.find(UUID.fromString(id)).orElseThrow(() -> new MissingRunException("Run not found: " + id));
        RunState state = Json.MAPPER.readValue(snapshot.stateJson(), RunState.class);
        state.revision = snapshot.revision();
        return state;
    }

    @Override
    public Lease lease(String id) throws IOException {
        RunLease lease = leases.acquire(id);
        return lease::close;
    }

    /** Fenced by revision: a writer that did not load the latest snapshot is refused. */
    @Override
    public void record(RunState state, String type, String detail) throws IOException {
        synchronized (state) {
            ObjectNode snapshot = Json.MAPPER.valueToTree(state);
            snapshot.put("revision", state.revision + 1);
            journal.record(
                    UUID.fromString(state.id), state.revision, Json.MAPPER.writeValueAsString(snapshot), type, detail);
            state.revision++;
        }
    }

    @Override
    public boolean auditValid(String id) throws IOException {
        return trail.verify(id);
    }

    /** The most recently updated runs, newest first. */
    public List<RunState> recentRuns() throws IOException {
        return parse(journal.recentStates());
    }

    /** Runs last updated before {@code cutoff}; terminal runs are not updated after they finish. */
    public List<RunState> runsUpdatedBefore(Instant cutoff) throws IOException {
        return parse(journal.statesUpdatedBefore(cutoff));
    }

    private static List<RunState> parse(List<String> states) throws IOException {
        List<RunState> runs = new ArrayList<>();
        for (String state : states) runs.add(Json.MAPPER.readValue(state, RunState.class));
        return runs;
    }
}
