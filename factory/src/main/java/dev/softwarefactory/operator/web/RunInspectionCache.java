package dev.softwarefactory.operator.web;

import dev.softwarefactory.persistence.ControlRepository.AuditEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded dashboard cache only. Never used to authorize workflow transitions. */
final class RunInspectionCache {
    private static final Duration TTL = Duration.ofSeconds(30);
    private final Clock clock;
    private final Map<String, Inspection> entries = new LinkedHashMap<>();

    record Inspection(long sequence, Instant checkedAt, boolean auditValid, List<AuditEvent> timeline) {
        Inspection { timeline = List.copyOf(timeline); }
    }
    @FunctionalInterface interface Loader { Inspection load(long sequence, Instant checkedAt) throws Exception; }

    RunInspectionCache(Clock clock) { this.clock = clock; }

    synchronized Inspection get(String id, long sequence, Loader loader) throws Exception {
        Instant now = clock.instant();
        Inspection previous = entries.get(id);
        if (previous != null && previous.sequence() == sequence && now.isBefore(previous.checkedAt().plus(TTL))) return previous;
        Inspection fresh = loader.load(sequence, now);
        entries.put(id, fresh);
        if (entries.size() > 128) entries.remove(entries.keySet().iterator().next());
        return fresh;
    }
}
