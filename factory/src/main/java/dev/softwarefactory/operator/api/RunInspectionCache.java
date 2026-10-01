package dev.softwarefactory.operator.api;

import dev.softwarefactory.audit.AuditEvent;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded dashboard cache only. Never used to authorize workflow transitions. */
final class RunInspectionCache {
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final int CAPACITY = 128;

    private final Clock clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    RunInspectionCache(Clock clock) {
        this.clock = clock;
    }

    record Inspection(long sequence, Instant checkedAt, boolean auditValid, List<AuditEvent> timeline) {
        Inspection {
            timeline = List.copyOf(timeline);
        }
    }

    @FunctionalInterface
    interface Loader {
        Inspection load(long sequence, Instant checkedAt) throws IOException;
    }

    /** One inspection per run, audit head and time window; its own lock so unrelated runs never wait for a load. */
    private static final class Entry {
        private final long sequence;
        private final Instant at;
        private Inspection inspection;

        Entry(long sequence, Instant at) {
            this.sequence = sequence;
            this.at = at;
        }

        synchronized Inspection load(Loader loader) throws IOException {
            if (inspection == null) inspection = loader.load(sequence, at);
            return inspection;
        }
    }

    /** A failed load is not cached: the next request inspects again. */
    Inspection get(String id, long sequence, Loader loader) throws IOException {
        Entry entry = entry(id, sequence);
        boolean loaded = false;
        try {
            Inspection inspection = entry.load(loader);
            loaded = true;
            return inspection;
        } finally {
            if (!loaded) {
                synchronized (entries) {
                    entries.remove(id, entry);
                }
            }
        }
    }

    private Entry entry(String id, long sequence) {
        synchronized (entries) {
            Instant now = clock.instant();
            Entry entry = entries.get(id);
            if (entry == null || entry.sequence != sequence || !now.isBefore(entry.at.plus(TTL))) {
                entry = new Entry(sequence, now);
                entries.put(id, entry);
                if (entries.size() > CAPACITY)
                    entries.remove(entries.keySet().iterator().next());
            }
            return entry;
        }
    }
}
