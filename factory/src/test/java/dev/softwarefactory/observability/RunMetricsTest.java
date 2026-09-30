package dev.softwarefactory.observability;

import dev.softwarefactory.persistence.ControlRepository.AuditEvent;
import dev.softwarefactory.workflow.RunState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunMetricsTest {
    private final Instant start = Instant.parse("2026-01-01T00:00:00Z");
    private AuditEvent event(int second, String type, String detail) {
        return new AuditEvent(second, start.plusSeconds(second), type, detail);
    }
    @Test void measuresRecoveredFailureAndKeepsUnresolvedFailuresVisible() {
        RunState state = new RunState(); state.startedAt = start; state.finishedAt = start.plusSeconds(40);
        var metrics = RunMetrics.from(state, List.of(
            event(1,"RETRY_AVAILABLE","patch:error"), event(2,"TASK_STARTED","patch"), event(3,"TASK_DONE","unrelated:hash"),
            event(11,"TASK_DONE","patch:hash"), event(12,"RETRY_AVAILABLE","tests:error"),
            event(13,"PARTIAL_REPLAN","stale=[tests]"), event(14,"RUN_RECOVERED","candidateReset=false"),
            event(15,"RUN_RECOVERED","candidateReset=true")), start.plusSeconds(99));
        assertEquals(40000, metrics.elapsedMillis());
        assertEquals(10000L, metrics.meanRecoveryMillis());
        assertEquals(1, metrics.unresolvedFailures());
        assertEquals(2, metrics.rollbacks());
        assertEquals(2, metrics.retryOffers());
        assertEquals(1, metrics.retryExecutions());
    }
    @Test void noRecoverySampleIsNotReportedAsZeroMttr() {
        RunState state = new RunState(); state.startedAt = start;
        var metrics = RunMetrics.from(state, List.of(), start.plusSeconds(5));
        assertNull(metrics.meanRecoveryMillis());
        assertFalse(metrics.terminal());
        assertEquals(5000, metrics.elapsedMillis());
    }
}
