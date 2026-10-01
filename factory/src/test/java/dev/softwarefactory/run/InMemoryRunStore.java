package dev.softwarefactory.run;

import dev.softwarefactory.platform.Json;
import dev.softwarefactory.platform.WorkflowConflictException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Copies on read/write to model persistence, rather than sharing a mutable RunState with the engine. */
final class InMemoryRunStore implements RunStore {
    private final Map<String, String> states = new HashMap<>();
    private final Set<String> leases = new HashSet<>();
    private final List<String> events = new ArrayList<>();

    @Override
    public synchronized RunState load(String id) throws IOException {
        String state = states.get(id);
        if (state == null) throw new MissingRunException("Run not found: " + id);
        return Json.MAPPER.readValue(state, RunState.class);
    }

    @Override
    public synchronized void record(RunState state, String type, String detail) throws IOException {
        states.put(state.id, Json.MAPPER.writeValueAsString(state));
        events.add(type + ":" + detail);
    }

    @Override
    public synchronized boolean auditValid(String id) {
        return states.containsKey(id);
    }

    @Override
    public synchronized Lease lease(String id) {
        if (!leases.add(id)) throw new WorkflowConflictException("Run is already being advanced");
        return () -> {
            synchronized (this) {
                leases.remove(id);
            }
        };
    }

    synchronized boolean has(String event) {
        return events.stream().anyMatch(value -> value.startsWith(event + ":"));
    }

    synchronized int eventCount() {
        return events.size();
    }

    /** Details of every recorded event of the given type, oldest first. */
    synchronized List<String> details(String event) {
        return events.stream()
                .filter(value -> value.startsWith(event + ":"))
                .map(value -> value.substring(event.length() + 1))
                .toList();
    }
}
