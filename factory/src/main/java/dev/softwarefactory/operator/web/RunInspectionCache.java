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

    private record Pending(long sequence, Instant at, java.util.concurrent.FutureTask<Inspection> task) {}

    private final Map<String, Pending> entries = new LinkedHashMap<>(16, 0.75f, true);

    record Inspection(long sequence, Instant checkedAt, boolean auditValid, List<AuditEvent> timeline) {
        Inspection {
            timeline = List.copyOf(timeline);
        }
    }

    @FunctionalInterface
    interface Loader {
        Inspection load(long sequence, Instant checkedAt) throws Exception;
    }

    RunInspectionCache(Clock clock) {
        this.clock = clock;
    }

    Inspection get(String id, long sequence, Loader loader) throws Exception {
        Pending pending;
        synchronized (entries) {
            Instant now = clock.instant();
            pending = entries.get(id);
            if (pending == null
                    || pending.sequence() != sequence
                    || !now.isBefore(pending.at().plus(TTL))) {
                pending = new Pending(
                        sequence, now, new java.util.concurrent.FutureTask<>(() -> loader.load(sequence, now)));
                entries.put(id, pending);
                if (entries.size() > 128)
                    entries.remove(entries.keySet().iterator().next());
            }
        }
        // FutureTask executes once per entry; unrelated runs never wait for this load.
        pending.task().run();
        try {
            return pending.task().get();
        } catch (java.util.concurrent.ExecutionException failed) {
            synchronized (entries) {
                entries.remove(id, pending);
            }
            if (failed.getCause() instanceof Exception cause) throw cause;
            throw new IllegalStateException("Inspection failed", failed.getCause());
        }
    }
}
