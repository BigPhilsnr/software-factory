package dev.softwarefactory.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** Metrics derived from the complete event history, never from model-reported results. */
public record RunMetrics(
        long elapsedMillis,
        boolean terminal,
        int retryOffers,
        int retryExecutions,
        int rollbacks,
        int recoveredTasks,
        int unresolvedFailures,
        Long meanRecoveryMillis,
        int parallelJoins,
        int replans,
        int approvalRequests) {
    /** The only event types these metrics read; dashboards may load just these. */
    public static final Set<String> EVENT_TYPES = Tally.COUNTERS.keySet();

    /**
     * @param finishedAt null while the run has not reached a terminal state; elapsed time then runs to {@code now}
     */
    public static RunMetrics from(Instant startedAt, Instant finishedAt, List<AuditEvent> events, Instant now) {
        Tally tally = new Tally();
        for (AuditEvent event : events) tally.count(event);
        return new RunMetrics(
                Math.max(
                        0,
                        Duration.between(startedAt, finishedAt == null ? now : finishedAt)
                                .toMillis()),
                finishedAt != null,
                tally.retries,
                tally.retryExecutions,
                tally.rollbacks,
                tally.recovered,
                tally.failures.size(),
                tally.recovered == 0 ? null : tally.recoveryMillis / tally.recovered,
                tally.joins,
                tally.replans,
                tally.approvals);
    }

    /** Running counts while replaying a timeline; {@code failures} holds tasks that failed and have not recovered. */
    private static final class Tally {
        /** How each event type changes the tally; other events do not affect reliability metrics. */
        private static final Map<String, BiConsumer<Tally, AuditEvent>> COUNTERS = Map.ofEntries(
                Map.entry(EventTypes.RETRY_AVAILABLE, Tally::retryOffered),
                Map.entry(EventTypes.TASK_FAILED, Tally::failed),
                Map.entry(EventTypes.POLICY_SAFE_STOP, Tally::failed),
                Map.entry(EventTypes.REVISION_REQUIRED, Tally::failed),
                Map.entry(EventTypes.TASK_STARTED, Tally::started),
                Map.entry(EventTypes.VALIDATION_STARTED, Tally::started),
                Map.entry(EventTypes.TASK_DONE, Tally::done),
                Map.entry(EventTypes.RUN_RECOVERED, Tally::runRecovered),
                Map.entry(EventTypes.PARTIAL_REPLAN, (tally, event) -> tally.replanned()),
                Map.entry(EventTypes.PARALLEL_JOIN, (tally, event) -> tally.joined()),
                Map.entry(EventTypes.APPROVAL_REQUIRED, (tally, event) -> tally.approvalRequested()));

        private final Map<String, Instant> failures = new HashMap<>();
        private long recoveryMillis;
        private int retries;
        private int retryExecutions;
        private int rollbacks;
        private int recovered;
        private int joins;
        private int replans;
        private int approvals;

        void count(AuditEvent event) {
            BiConsumer<Tally, AuditEvent> counter = COUNTERS.get(event.type());
            if (counter != null) counter.accept(this, event);
        }

        private static String task(AuditEvent event) {
            return event.detail().split(":", 2)[0];
        }

        private void retryOffered(AuditEvent event) {
            retries++;
            failed(event);
        }

        private void failed(AuditEvent event) {
            failures.putIfAbsent(task(event), event.at());
        }

        private void started(AuditEvent event) {
            if (failures.containsKey(task(event))) retryExecutions++;
        }

        private void done(AuditEvent event) {
            recover(task(event), event.at());
        }

        private void runRecovered(AuditEvent event) {
            if (event.detail().contains("candidateReset=true")) rollbacks++;
        }

        private void replanned() {
            rollbacks++;
            replans++;
        }

        private void joined() {
            joins++;
        }

        private void approvalRequested() {
            approvals++;
        }

        private void recover(String task, Instant at) {
            Instant failedAt = failures.remove(task);
            if (failedAt != null) {
                recovered++;
                recoveryMillis += Math.max(0, Duration.between(failedAt, at).toMillis());
            }
        }
    }
}
