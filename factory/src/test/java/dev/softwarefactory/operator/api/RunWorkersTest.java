package dev.softwarefactory.operator.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.softwarefactory.platform.WorkflowConflictException;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RunWorkersTest {
    @Test
    void aRunIsBusyWhileItsWorkerAdvancesItAndCannotBeStartedTwice() throws Exception {
        var release = new CountDownLatch(1);
        var workers = new RunWorkers(ConcurrentHashMap.newKeySet());
        workers.start("run", release::await);
        assertTrue(workers.busy("run"));
        assertThrows(WorkflowConflictException.class, () -> workers.requireCapacity("run"));
        assertThrows(WorkflowConflictException.class, () -> workers.start("run", () -> {}));
        release.countDown();
        workers.close();
        assertFalse(workers.busy("run"));
        assertEquals("", workers.lastError("run"));
    }

    @Test
    void capacityIsTwoRunsAndNothingIsAcceptedAfterShutdown() {
        var release = new CountDownLatch(1);
        var workers = new RunWorkers(ConcurrentHashMap.newKeySet());
        workers.start("one", release::await);
        workers.requireCapacity("two");
        workers.start("two", release::await);
        var full = assertThrows(ServiceUnavailableException.class, () -> workers.requireCapacity("three"));
        assertTrue(full.getMessage().startsWith("Two runs are active"));
        release.countDown();
        workers.close();
        var closed = assertThrows(ServiceUnavailableException.class, () -> workers.requireCapacity("three"));
        assertTrue(closed.getMessage().contains("shutting down"));
    }

    @Test
    void aFailedAdvanceIsKeptForTheOperatorAndClearedByTheNextAttempt() {
        var workers = new RunWorkers(ConcurrentHashMap.newKeySet());
        workers.start("run", () -> {
            throw new IOException("control database unavailable");
        });
        workers.start("other", () -> {
            throw new IllegalStateException("unexpected");
        });
        workers.start("interrupted", () -> {
            throw new InterruptedException("shutdown");
        });
        awaitIdle(workers, "run", "other", "interrupted");
        assertEquals(
                "Advance failed: IOException. Inspect the audit and retry after resolving the issue.",
                workers.lastError("run"));
        assertTrue(workers.lastError("other").contains("IllegalStateException"));
        assertTrue(workers.lastError("interrupted").contains("InterruptedException"));
        workers.start("run", () -> {});
        awaitIdle(workers, "run");
        assertEquals("", workers.lastError("run"), "The error only describes the latest attempt");
        workers.close();
    }

    private static void awaitIdle(RunWorkers workers, String... ids) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (String id : ids) {
            while (workers.busy(id) && System.nanoTime() < deadline) Thread.onSpinWait();
            assertFalse(workers.busy(id), id + " is still busy");
        }
    }
}
