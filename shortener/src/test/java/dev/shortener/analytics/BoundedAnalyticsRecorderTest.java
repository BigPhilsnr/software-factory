package dev.shortener.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.shortener.ShortenerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class BoundedAnalyticsRecorderTest {
    private static final Instant T0 = Instant.parse("2026-10-01T12:00:00Z");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(T0);
    private final List<List<RedirectDelta>> writes = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failing = new AtomicBoolean();

    private final RedirectStatsWriter writer = deltas -> {
        if (failing.get()) throw new DataAccessResourceFailureException("database offline");
        writes.add(List.copyOf(deltas));
    };

    private BoundedAnalyticsRecorder recorder(int maxPendingLinks, Duration flushInterval) {
        var settings = new ShortenerProperties.Analytics(
                flushInterval,
                maxPendingLinks,
                500,
                Duration.ofSeconds(30),
                2,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2));
        return new BoundedAnalyticsRecorder(writer, clock, settings, meters);
    }

    private double counter(String name, String... tags) {
        return meters.get(name).tags(tags).counter().count();
    }

    @Test
    void coalescesRedirectsIntoOneDeltaPerLinkWithTheLatestTimestamp() {
        var recorder = recorder(100, Duration.ofHours(1));
        recorder.record(2);
        recorder.record(1);
        clock.value = T0.plusSeconds(5);
        recorder.record(1);
        recorder.record(1);
        recorder.flush();
        assertEquals(List.of(List.of(new RedirectDelta(1, 3, T0.plusSeconds(5)), new RedirectDelta(2, 1, T0))), writes);
        assertEquals(4, counter("shortener.analytics.recorded"));
        assertEquals(4, counter("shortener.analytics.flushed"));
        assertEquals(0, meters.get("shortener.analytics.pending").gauge().value());
        recorder.flush();
        assertEquals(1, writes.size(), "an empty flush writes nothing");
    }

    @Test
    void dropsAndCountsRedirectsForNewLinksBeyondThePendingBound() {
        var recorder = recorder(2, Duration.ofHours(1));
        recorder.record(1);
        recorder.record(2);
        recorder.record(3);
        recorder.record(1);
        assertEquals(1, counter("shortener.analytics.dropped", "reason", "overflow"));
        recorder.flush();
        assertEquals(List.of(new RedirectDelta(1, 2, T0), new RedirectDelta(2, 1, T0)), writes.getFirst());
    }

    @Test
    void failedFlushIsMergedBackAndRetried() {
        var recorder = recorder(100, Duration.ofHours(1));
        recorder.record(1);
        recorder.record(1);
        failing.set(true);
        recorder.flush();
        assertEquals(1, counter("shortener.analytics.flush.failures"));
        assertTrue(writes.isEmpty());
        clock.value = T0.plusSeconds(1);
        recorder.record(1);
        failing.set(false);
        recorder.flush();
        assertEquals(List.of(new RedirectDelta(1, 3, T0.plusSeconds(1))), writes.getFirst());
    }

    @Test
    void failedFlushDropsOnlyWhatNoLongerFits() {
        var recorder = recorder(1, Duration.ofHours(1));
        recorder.record(1);
        failing.set(true);
        recorder.flush();
        recorder.record(2); // Re-merged link 1 still occupies the only slot.
        assertEquals(1, counter("shortener.analytics.dropped", "reason", "overflow"));
        failing.set(false);
        recorder.flush();
        assertEquals(List.of(new RedirectDelta(1, 1, T0)), writes.getFirst());
    }

    @Test
    void flushesPeriodicallyAndOnShutdown() {
        var recorder = recorder(100, Duration.ofMillis(20));
        recorder.start();
        assertTrue(recorder.isRunning());
        recorder.record(1);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !writes.isEmpty());
        recorder.record(1);
        recorder.record(1);
        recorder.stop();
        assertTrue(!recorder.isRunning());
        long total = writes.stream()
                .flatMap(List::stream)
                .mapToLong(RedirectDelta::count)
                .sum();
        assertEquals(3, total, "the shutdown flush writes the remainder");
    }

    @Test
    void concurrentRecordingAndFlushingLosesNothing() throws Exception {
        var recorder = recorder(1_000, Duration.ofHours(1));
        int threads = 8;
        int perThread = 20_000;
        int links = 5;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        try (var pool = Executors.newFixedThreadPool(threads + 1)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) recorder.record(i % links);
                    done.countDown();
                    return null;
                });
            }
            pool.submit(() -> {
                start.await();
                while (done.getCount() > 0) recorder.flush();
                return null;
            });
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        }
        recorder.flush();
        Map<Long, Long> totals = new ConcurrentHashMap<>();
        writes.stream().flatMap(List::stream).forEach(delta -> totals.merge(delta.linkId(), delta.count(), Long::sum));
        for (long link = 0; link < links; link++)
            assertEquals((long) threads * perThread / links, totals.get(link), "link " + link);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant value;

        MutableClock(Instant value) {
            this.value = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return value;
        }
    }
}
