package dev.shortener.analytics;

import dev.shortener.ShortenerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Coalesces redirects in memory and periodically writes one batched delta per link, so a hot link costs one
 * row update per flush instead of one per redirect. {@link #record} never blocks or touches the database.
 * The number of distinct pending links is bounded; redirects beyond it are dropped and counted. A failed
 * flush is merged back (within the same bound) and retried on the next flush.
 */
public final class BoundedAnalyticsRecorder implements AnalyticsRecorder, SmartLifecycle {
    /** Stops after the web server has drained, so the final flush sees every served redirect. */
    private static final int LIFECYCLE_PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    private static final Logger LOG = LoggerFactory.getLogger(BoundedAnalyticsRecorder.class);
    private static final String DROPPED = "shortener.analytics.dropped";
    private static final String REASON = "reason";
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private final RedirectStatsWriter writer;
    private final Clock clock;
    private final int maxPendingLinks;
    private final Duration flushInterval;
    private final Duration failureLogInterval;
    private final Counter recorded;
    private final Counter flushed;
    private final Counter droppedOverflow;
    private final Counter droppedAfterFailure;
    private final Counter flushFailures;
    private final AtomicLong lastFailureLog = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong suppressedFailureLogs = new AtomicLong();
    /** Serializes scheduled and shutdown flushes; never taken by {@link #record}. */
    private final Object flushLock = new Object();

    private ScheduledExecutorService scheduler;

    public BoundedAnalyticsRecorder(
            RedirectStatsWriter writer, Clock clock, ShortenerProperties.Analytics settings, MeterRegistry meters) {
        this.writer = writer;
        this.clock = clock;
        this.maxPendingLinks = settings.maxPendingLinks();
        this.flushInterval = settings.flushInterval();
        this.failureLogInterval = settings.failureLogInterval();
        this.recorded = Counter.builder("shortener.analytics.recorded")
                .description("Redirects accepted for recording")
                .register(meters);
        this.flushed = Counter.builder("shortener.analytics.flushed")
                .description("Redirects durably written")
                .register(meters);
        this.droppedOverflow = Counter.builder(DROPPED).tag(REASON, "overflow").register(meters);
        this.droppedAfterFailure =
                Counter.builder(DROPPED).tag(REASON, "flush_failed").register(meters);
        this.flushFailures =
                Counter.builder("shortener.analytics.flush.failures").register(meters);
        Gauge.builder("shortener.analytics.pending", pending, ConcurrentHashMap::size)
                .description("Links with redirects awaiting flush")
                .register(meters);
    }

    @Override
    public void record(long linkId) {
        if (merge(linkId, 1, clock.instant())) recorded.increment();
        else droppedOverflow.increment();
    }

    /** Writes everything pending; safe to call concurrently with {@link #record}. */
    public void flush() {
        synchronized (flushLock) {
            List<RedirectDelta> batch = drain();
            if (batch.isEmpty()) return;
            try {
                writer.write(batch);
                flushed.increment(batch.stream().mapToLong(RedirectDelta::count).sum());
            } catch (RuntimeException failure) {
                flushFailures.increment();
                logFailure(batch.size(), failure);
                for (RedirectDelta delta : batch) {
                    if (!merge(delta.linkId(), delta.count(), delta.lastRedirectAt()))
                        droppedAfterFailure.increment(delta.count());
                }
            }
        }
    }

    private List<RedirectDelta> drain() {
        List<RedirectDelta> batch = new ArrayList<>();
        pending.forEach((linkId, entry) -> {
            if (pending.remove(linkId, entry)) {
                long count = entry.retire();
                if (count > 0) batch.add(new RedirectDelta(linkId, count, entry.latest()));
            }
        });
        batch.sort(Comparator.comparingLong(RedirectDelta::linkId));
        return batch;
    }

    /** Lock-free; the pending-link bound may be overshot by at most the number of concurrent callers. */
    private boolean merge(long linkId, long count, Instant at) {
        while (true) {
            Pending entry = pending.get(linkId);
            if (entry == null) {
                if (pending.size() >= maxPendingLinks) return false;
                entry = pending.computeIfAbsent(linkId, id -> new Pending(at));
            }
            if (entry.add(count, at)) return true;
            pending.remove(linkId, entry); // Retired by a concurrent flush; start a fresh entry.
        }
    }

    private void logFailure(int links, RuntimeException failure) {
        long now = clock.millis();
        long previous = lastFailureLog.get();
        if (now - previous >= failureLogInterval.toMillis() && lastFailureLog.compareAndSet(previous, now)) {
            LOG.warn(
                    "Analytics flush of {} links failed; counts retained for retry ({} similar warnings suppressed)",
                    links,
                    suppressedFailureLogs.getAndSet(0),
                    failure);
        } else {
            suppressedFailureLogs.incrementAndGet();
        }
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "analytics-flush");
            thread.setDaemon(true);
            return thread;
        });
        long interval = flushInterval.toMillis();
        scheduler.scheduleWithFixedDelay(this::flush, interval, interval, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        ScheduledExecutorService running;
        synchronized (this) {
            running = scheduler;
            scheduler = null;
        }
        if (running == null) return;
        running.shutdown();
        try {
            if (!running.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) running.shutdownNow();
        } catch (InterruptedException interrupted) {
            running.shutdownNow();
            Thread.currentThread().interrupt();
        }
        flush();
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }

    @Override
    public int getPhase() {
        return LIFECYCLE_PHASE;
    }

    /** Per-link accumulator; once retired by a flush it rejects further additions. */
    private static final class Pending {
        private static final long RETIRED = -1;
        private final AtomicLong count = new AtomicLong();
        private final AtomicReference<Instant> latest;

        Pending(Instant at) {
            this.latest = new AtomicReference<>(at);
        }

        boolean add(long redirects, Instant at) {
            // Timestamp first: a flush that observes the count therefore also observes its timestamp.
            latest.accumulateAndGet(at, (current, candidate) -> candidate.isAfter(current) ? candidate : current);
            long current;
            do {
                current = count.get();
                if (current == RETIRED) return false;
            } while (!count.compareAndSet(current, current + redirects));
            return true;
        }

        long retire() {
            return count.getAndSet(RETIRED);
        }

        Instant latest() {
            return latest.get();
        }
    }
}
