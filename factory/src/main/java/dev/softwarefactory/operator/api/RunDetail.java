package dev.softwarefactory.operator.api;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.audit.RunMetrics;
import dev.softwarefactory.run.RunState;
import dev.softwarefactory.run.RunStatus;
import dev.softwarefactory.run.TaskStatus;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code GET /factory/api/runs/{id}}: everything the operator page shows for one run - state, tasks, events,
 * audit verdict, evidence to read, and what the run is waiting for.
 */
final class RunDetail {
    /** Pause reasons shown to the operator, newest first. */
    private static final Set<String> PAUSE_EVENTS =
            Set.of(EventTypes.RETRY_AVAILABLE, EventTypes.INFRASTRUCTURE_UNAVAILABLE, EventTypes.REVISION_REQUIRED);

    private static final String TASK = "task";
    private static final String TEXT = "text";
    private static final String EVIDENCE_SUFFIX = ".txt";

    private final EvidenceFiles evidence;

    RunDetail(EvidenceFiles evidence) {
        this.evidence = evidence;
    }

    /** What changes between requests for the same run state: worker activity and the cached audit inspection. */
    record Activity(
            boolean busy, String error, List<Map<String, Object>> events, RunInspectionCache.Inspection inspection) {}

    Map<String, Object> describe(RunState state, ScenarioSpec spec, Activity activity) throws IOException {
        List<String> artifacts = evidence.names(state.id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state);
        result.put("requirement", spec.requirement());
        result.put("tasks", spec.tasks());
        result.put("busy", activity.busy());
        result.put("error", activity.error());
        result.put("events", activity.events());
        result.put(
                "metrics",
                RunMetrics.from(
                        state.startedAt, state.finishedAt, activity.inspection().timeline(), Instant.now()));
        result.put("auditValid", activity.inspection().auditValid());
        result.put("auditCheckedAt", activity.inspection().checkedAt());
        result.put("artifacts", artifacts);
        result.put("clarificationContext", clarificationContext(state, spec));
        result.put("validationEvidence", validationEvidence(state, spec));
        if (state.status == RunStatus.PAUSED
                && state.pendingApprovalTask == null
                && state.pendingClarificationTask == null) {
            activity.events().stream()
                    .filter(event -> PAUSE_EVENTS.contains(String.valueOf(event.get("type"))))
                    .findFirst()
                    .ifPresent(event -> putPauseReason(result, state.id, artifacts, event));
        }
        if (state.pendingApprovalTask != null) result.put("review", review(state));
        return result;
    }

    /** The upstream outputs an operator needs to answer the pending clarification. */
    private List<Map<String, String>> clarificationContext(RunState state, ScenarioSpec spec) {
        List<Map<String, String>> context = new ArrayList<>();
        for (TaskSpec task : spec.tasks()) {
            if (!task.id().equals(state.pendingClarificationTask)) continue;
            for (String dependency : task.dependsOn()) {
                Integer version = state.artifactVersions.get(dependency);
                if (version != null) context.add(Map.of(TASK, dependency, TEXT, output(state.id, dependency, version)));
            }
        }
        return context;
    }

    private List<Map<String, String>> validationEvidence(RunState state, ScenarioSpec spec) {
        List<Map<String, String>> reports = new ArrayList<>();
        for (TaskSpec task : spec.tasks()) {
            Integer version = state.artifactVersions.get(task.id());
            if (task.kind().isValidation() && state.tasks.get(task.id()) == TaskStatus.DONE && version != null) {
                reports.add(Map.of(TASK, task.id(), TEXT, output(state.id, task.id(), version)));
            }
        }
        return reports;
    }

    private Map<String, Object> review(RunState state) {
        String name = state.pendingApprovalTask + "-v" + state.artifactVersions.get(state.pendingApprovalTask)
                + EVIDENCE_SUFFIX;
        return Map.of(
                TASK,
                state.pendingApprovalTask,
                "hash",
                state.pendingApprovalHash,
                "artifact",
                name,
                "patch",
                evidence.display(state.id, name),
                "baseline",
                state.baselineCommit);
    }

    /** Exposes why a paused run stopped: retry offered, platform unavailable, or revision required. */
    private void putPauseReason(
            Map<String, Object> result, String id, List<String> artifacts, Map<String, Object> event) {
        String detail = String.valueOf(event.get("detail"));
        String[] parts = detail.split(":", 2);
        String reason = parts.length == 2 && artifacts.contains(parts[1] + EVIDENCE_SUFFIX)
                ? detail + "\n" + evidence.display(id, parts[1] + EVIDENCE_SUFFIX)
                : detail;
        result.put(
                "pauseKind",
                switch (String.valueOf(event.get("type"))) {
                    case EventTypes.INFRASTRUCTURE_UNAVAILABLE -> "infrastructure";
                    case EventTypes.REVISION_REQUIRED -> "revision";
                    default -> "retry";
                });
        result.put("retryReason", reason);
    }

    private String output(String id, String task, Integer version) {
        return evidence.display(id, task + "-v" + version + EVIDENCE_SUFFIX);
    }
}
