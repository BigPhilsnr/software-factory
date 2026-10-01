package dev.softwarefactory.operator.web;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RunInspectionCacheTest {
    @Test void refreshesOnAuditAppendAndExpiresEvenWithoutAppend() throws Exception {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(start);
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        var cache = new RunInspectionCache(clock);
        AtomicInteger reads = new AtomicInteger();
        RunInspectionCache.Loader loader = (seq, at) -> new RunInspectionCache.Inspection(seq, at, reads.incrementAndGet() < 3, List.of());
        var first = cache.get("run", 1, loader);
        assertSame(first, cache.get("run", 1, loader));
        assertNotSame(first, cache.get("run", 2, loader));
        assertEquals(2, reads.get());
        now.set(start.plusSeconds(30));
        assertFalse(cache.get("run", 2, loader).auditValid(), "Recheck historical tampering even with unchanged head");
        assertEquals(3, reads.get());
    }

    @Test void evictsOldEntriesAndDoesNotCacheLoaderFailures() throws Exception {
        var cache = new RunInspectionCache(Clock.systemUTC());
        AtomicInteger reads = new AtomicInteger();
        RunInspectionCache.Loader loader = (seq, at) -> {
            reads.incrementAndGet();
            return new RunInspectionCache.Inspection(seq, at, true, List.of());
        };
        for (int i = 0; i < 129; i++) cache.get("run-" + i, 1, loader);
        cache.get("run-0", 1, loader);
        assertEquals(130, reads.get());
        assertThrows(IllegalStateException.class, () -> cache.get("failed", 1, (seq, at) -> { throw new IllegalStateException("offline"); }));
        assertTrue(cache.get("failed", 1, loader).auditValid());
    }
}
