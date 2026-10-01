package dev.softwarefactory.operator.api;

import dev.softwarefactory.platform.LogText;
import dev.softwarefactory.platform.LruMap;
import dev.softwarefactory.platform.WorkflowConflictException;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Background workers that advance runs, at most two runs at a time. A request returns as soon as its run
 * is queued; the outcome is read from the run's state, or from the last error kept here.
 */
final class RunWorkers implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RunWorkers.class);
    private static final int MAX_ACTIVE_RUNS = 2;
    private static final Duration DRAIN = Duration.ofSeconds(30);
    private static final Duration FORCED_STOP = Duration.ofSeconds(5);
    private static final String ALREADY_ACTIVE = "This run is already active";

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> active;
    private final Map<String, String> errors = LruMap.create(LruMap.DEFAULT_CAPACITY);

    /**
     * @param active the IDs of runs being advanced; shared and thread-safe
     */
    RunWorkers(Set<String> active) {
        this.active = active;
    }

    @FunctionalInterface
    interface Advance {
        void run() throws IOException, InterruptedException;
    }

    boolean busy(String id) {
        return active.contains(id);
    }

    /** Why the last advance of this run failed on its worker, or an empty string. */
    String lastError(String id) {
        return errors.getOrDefault(id, "");
    }

    /**
     * @throws ServiceUnavailableException when shutting down or two other runs are active
     * @throws WorkflowConflictException when this run is already being advanced
     */
    void requireCapacity(String id) {
        if (workers.isShutdown())
            throw new ServiceUnavailableException("Factory is shutting down; no decision was recorded");
        if (active.contains(id)) throw new WorkflowConflictException(ALREADY_ACTIVE);
        if (active.size() >= MAX_ACTIVE_RUNS) {
            throw new ServiceUnavailableException(
                    "Two runs are active; no decision was recorded. Retry when capacity is available.");
        }
    }

    void start(String id, Advance advance) {
        if (!active.add(id)) throw new WorkflowConflictException(ALREADY_ACTIVE);
        errors.remove(id);
        workers.submit(() -> run(id, advance));
    }

    /**
     * Stops accepting work, then drains workers for a bounded time. Callers are not blocked behind the
     * drain: {@link #requireCapacity} fails fast with "shutting down".
     */
    @Override
    public void close() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(DRAIN.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warn("Workers did not finish within {}s; interrupting them", DRAIN.toSeconds());
                workers.shutdownNow();
                if (!workers.awaitTermination(FORCED_STOP.toMillis(), TimeUnit.MILLISECONDS))
                    LOG.error("Workers ignored interruption");
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Worker-thread boundary: a failed advance is logged and shown to the operator, never lost. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // Nothing above a worker thread could handle it.
    private void run(String id, Advance advance) {
        try (var ignoredContext = MDC.putCloseable("runId", id)) {
            advance.run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            recordFailure(id, interrupted);
        } catch (IOException | RuntimeException failure) {
            recordFailure(id, failure);
        } finally {
            active.remove(id);
        }
    }

    private void recordFailure(String id, Exception failure) {
        LOG.error("Advance of run {} failed", LogText.singleLine(id), failure);
        errors.put(
                id,
                "Advance failed: " + failure.getClass().getSimpleName()
                        + ". Inspect the audit and retry after resolving the issue.");
    }
}
