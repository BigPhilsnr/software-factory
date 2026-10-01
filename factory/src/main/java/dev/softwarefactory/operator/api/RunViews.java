package dev.softwarefactory.operator.api;

import dev.softwarefactory.audit.RunMetrics;
import dev.softwarefactory.run.RunState;
import dev.softwarefactory.scenario.ScenarioFiles;
import dev.softwarefactory.scenario.ScenarioSpec;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What the operator reads: the recent runs, one run in detail, fleet metrics and evidence files. */
final class RunViews {
    private final ControlRecords records;
    private final EvidenceFiles evidence;
    private final RunDetail runDetail;
    private final RunInspectionCache inspections = new RunInspectionCache(Clock.systemUTC());

    RunViews(Path root, ControlRecords records) {
        this.records = records;
        this.evidence = new EvidenceFiles(root.resolve("evidence"));
        this.runDetail = new RunDetail(evidence);
    }

    List<RunState> runs() throws IOException {
        return records.runs().recentRuns();
    }

    Map<String, Object> metrics() throws IOException {
        return MetricsSummary.of(runs(), records.trail().recentTimelines(RunMetrics.EVENT_TYPES), Instant.now());
    }

    /**
     * @param busy whether a worker is advancing the run right now
     * @param error the last worker failure of this run, or an empty string
     */
    Map<String, Object> detail(RunState state, boolean busy, String error) throws IOException {
        String id = state.id;
        ScenarioSpec spec = scenario(state);
        var events = records.trail().recentEvents(id);
        long sequence = events.isEmpty() ? 0 : ((Number) events.getFirst().get("sequence")).longValue();
        // The audit chain is re-verified when its head moves or the cached verdict expires, not on every poll.
        var inspection = inspections.get(
                id,
                sequence,
                (head, at) -> new RunInspectionCache.Inspection(
                        head, at, records.runs().auditValid(id), records.trail().timeline(id)));
        return runDetail.describe(state, spec, new RunDetail.Activity(busy, error, events, inspection));
    }

    String artifact(String id, String name) throws IOException {
        return evidence.read(id, name);
    }

    /**
     * @throws NotFoundException when the run's scenario file no longer exists
     */
    static ScenarioSpec scenario(RunState state) throws IOException {
        try {
            return ScenarioFiles.read(Path.of(state.specPath)).spec();
        } catch (NoSuchFileException missing) {
            throw new NotFoundException("Scenario specification not found for run " + state.id, missing);
        }
    }
}
