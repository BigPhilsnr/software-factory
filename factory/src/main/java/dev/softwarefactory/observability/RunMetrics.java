package dev.softwarefactory.observability;

import dev.softwarefactory.persistence.ControlRepository.AuditEvent;
import dev.softwarefactory.workflow.RunState;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    public static final Set<String> EVENT_TYPES = Set.of(
            "RETRY_AVAILABLE",
            "TASK_FAILED",
            "POLICY_SAFE_STOP",
            "REVISION_REQUIRED",
            "TASK_STARTED",
            "VALIDATION_STARTED",
            "TASK_DONE",
            "RUN_RECOVERED",
            "PARTIAL_REPLAN",
            "PARALLEL_JOIN",
            "APPROVAL_REQUIRED");

    public static RunMetrics from(RunState state, List<AuditEvent> events, Instant now) {
        Map<String, Instant> failures = new HashMap<>();
        long recoveryMillis = 0;
        int retries = 0, retryExecutions = 0, rollbacks = 0, recovered = 0, joins = 0, replans = 0, approvals = 0;
        for (AuditEvent event : events) {
            String task = event.detail().split(":", 2)[0];
            switch (event.type()) {
                case "RETRY_AVAILABLE" -> {
                    retries++;
                    failures.putIfAbsent(task, event.at());
                }
                case "TASK_FAILED", "POLICY_SAFE_STOP", "REVISION_REQUIRED" -> failures.putIfAbsent(task, event.at());
                case "TASK_STARTED", "VALIDATION_STARTED" -> {
                    if (failures.containsKey(task)) retryExecutions++;
                }
                case "TASK_DONE" -> {
                    Instant failedAt = failures.remove(task);
                    if (failedAt != null) {
                        recovered++;
                        recoveryMillis += Math.max(
                                0, Duration.between(failedAt, event.at()).toMillis());
                    }
                }
                case "RUN_RECOVERED" -> {
                    if (event.detail().contains("candidateReset=true")) rollbacks++;
                }
                case "PARTIAL_REPLAN" -> {
                    rollbacks++;
                    replans++;
                }
                case "PARALLEL_JOIN" -> joins++;
                case "APPROVAL_REQUIRED" -> approvals++;
                default -> {}
            }
        }
        return new RunMetrics(
                Math.max(
                        0,
                        Duration.between(state.startedAt, state.finishedAt == null ? now : state.finishedAt)
                                .toMillis()),
                state.finishedAt != null,
                retries,
                retryExecutions,
                rollbacks,
                recovered,
                failures.size(),
                recovered == 0 ? null : recoveryMillis / recovered,
                joins,
                replans,
                approvals);
    }
}
