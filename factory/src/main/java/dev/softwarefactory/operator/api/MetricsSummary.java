package dev.softwarefactory.operator.api;

import dev.softwarefactory.audit.AuditEvent;
import dev.softwarefactory.audit.RunJournal;
import dev.softwarefactory.audit.RunMetrics;
import dev.softwarefactory.run.RunMode;
import dev.softwarefactory.run.RunState;
import dev.softwarefactory.run.RunStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** {@code GET /factory/api/metrics}: outcome and reliability figures over the most recently updated runs. */
final class MetricsSummary {
    private MetricsSummary() {}

    static Map<String, Object> of(List<RunState> runs, Map<String, List<AuditEvent>> timelines, Instant now) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put(
                "sample", "Latest " + RunJournal.RECENT_RUNS + " updated runs; fixture and live outcomes are separate");
        for (String mode : List.of(RunMode.FIXTURE, RunMode.LIVE)) {
            List<RunState> sample =
                    runs.stream().filter(run -> mode.equals(run.mode)).toList();
            List<RunMetrics> measurements = sample.stream()
                    .map(run -> RunMetrics.from(
                            run.startedAt, run.finishedAt, timelines.getOrDefault(run.id, List.of()), now))
                    .toList();
            summary.put(mode, modeSummary(sample, measurements));
        }
        return summary;
    }

    private static Map<String, Object> modeSummary(List<RunState> sample, List<RunMetrics> measurements) {
        long ended = sample.stream().filter(run -> run.finishedAt != null).count();
        long completed =
                sample.stream().filter(run -> run.status == RunStatus.COMPLETED).count();
        int recovered =
                measurements.stream().mapToInt(RunMetrics::recoveredTasks).sum();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("runs", sample.size());
        values.put("terminalRuns", ended);
        values.put("completedRuns", completed);
        values.put("completionRate", ended == 0 ? null : (double) completed / ended);
        values.put(
                "outcomes",
                sample.stream().collect(Collectors.groupingBy(run -> run.status.toString(), Collectors.counting())));
        values.put(
                "retryExecutions",
                measurements.stream().mapToInt(RunMetrics::retryExecutions).sum());
        values.put(
                "rollbacks",
                measurements.stream().mapToInt(RunMetrics::rollbacks).sum());
        values.put("retryRunRate", share(measurements, metrics -> metrics.retryExecutions() > 0));
        values.put("rollbackRunRate", share(measurements, metrics -> metrics.rollbacks() > 0));
        values.put("meanTerminalLatencyMillis", ended == 0 ? null : meanTerminalLatency(measurements));
        values.put("recoveredTasks", recovered);
        values.put("meanRecoveryMillis", recovered == 0 ? null : totalRecoveryMillis(measurements) / recovered);
        return values;
    }

    /** The fraction of runs matching the condition, or null for an empty sample. */
    private static Double share(List<RunMetrics> measurements, Predicate<RunMetrics> condition) {
        if (measurements.isEmpty()) return null;
        return (double) measurements.stream().filter(condition).count() / measurements.size();
    }

    private static double meanTerminalLatency(List<RunMetrics> measurements) {
        return measurements.stream()
                .filter(RunMetrics::terminal)
                .mapToLong(RunMetrics::elapsedMillis)
                .average()
                .orElseThrow();
    }

    private static long totalRecoveryMillis(List<RunMetrics> measurements) {
        return measurements.stream()
                .filter(metrics -> metrics.meanRecoveryMillis() != null)
                .mapToLong(metrics -> metrics.meanRecoveryMillis() * metrics.recoveredTasks())
                .sum();
    }
}
