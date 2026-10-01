package dev.softwarefactory.operator.web;

import dev.softwarefactory.workflow.scenario.ScenarioFiles;
import dev.softwarefactory.observability.RunMetrics;

import dev.softwarefactory.agents.AdkClaudeRuntime;
import dev.softwarefactory.persistence.ControlRepository;
import dev.softwarefactory.serialization.Json;
import dev.softwarefactory.workflow.RunEngine;
import dev.softwarefactory.workflow.RunState;
import dev.softwarefactory.workflow.RunStatus;
import dev.softwarefactory.workflow.TaskKind;
import dev.softwarefactory.workflow.TaskStatus;
import dev.softwarefactory.workflow.scenario.ScenarioSpec;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Shared browser/chat application service. Database leases remain the transition authority. */
public final class FactoryService implements AutoCloseable {
    private static final Set<String> SCENARIOS = Set.of("greenfield", "brownfield", "ambiguous", "bugfix");
    private final Path root;
    private final ControlRepository repository;
    private final RunEngine engine;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private final RunInspectionCache inspections = new RunInspectionCache(java.time.Clock.systemUTC());

    public FactoryService(Path root, ControlRepository repository) {
        this.root = root.toAbsolutePath().normalize();
        this.repository = repository;
        this.engine = new RunEngine(repository, this.root);
    }

    ChatConversation conversation() {
        return new ChatConversation(root, (role, prompt) -> new AdkClaudeRuntime(
            System.getenv().getOrDefault("CLAUDE_MODEL", "claude-sonnet-4-5"), root, () -> {}, (event, detail) -> {}).generate(role, prompt));
    }

    public List<RunState> runs() throws Exception { return repository.recentRuns(); }
    public Map<String, Object> metrics() throws Exception {
        List<RunState> runs = runs();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sample", "Latest 100 updated runs; fixture and live outcomes are separate");
        for (String mode : List.of("fixture", "live")) {
            List<RunState> sample = runs.stream().filter(r -> mode.equals(r.mode)).toList();
            long ended = sample.stream().filter(r -> r.finishedAt != null).count();
            long completed = sample.stream().filter(r -> r.status == RunStatus.COMPLETED).count();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("runs", sample.size()); values.put("terminalRuns", ended); values.put("completedRuns", completed);
            values.put("completionRate", ended == 0 ? null : (double) completed / ended);
            values.put("outcomes", sample.stream().collect(java.util.stream.Collectors.groupingBy(r -> r.status.toString(), java.util.stream.Collectors.counting())));
            List<RunMetrics> measurements = new ArrayList<>();
            for (RunState run : sample) measurements.add(RunMetrics.from(run, repository.timeline(run.id), java.time.Instant.now()));
            values.put("retryExecutions", measurements.stream().mapToInt(RunMetrics::retryExecutions).sum());
            values.put("rollbacks", measurements.stream().mapToInt(RunMetrics::rollbacks).sum());
            values.put("retryRunRate", sample.isEmpty() ? null : (double) measurements.stream().filter(m -> m.retryExecutions() > 0).count() / sample.size());
            values.put("rollbackRunRate", sample.isEmpty() ? null : (double) measurements.stream().filter(m -> m.rollbacks() > 0).count() / sample.size());
            values.put("meanTerminalLatencyMillis", ended == 0 ? null : measurements.stream().filter(RunMetrics::terminal).mapToLong(RunMetrics::elapsedMillis).average().orElseThrow());
            int recovered = measurements.stream().mapToInt(RunMetrics::recoveredTasks).sum();
            values.put("recoveredTasks", recovered);
            values.put("meanRecoveryMillis", recovered == 0 ? null : measurements.stream().filter(m -> m.meanRecoveryMillis() != null).mapToLong(m -> m.meanRecoveryMillis() * m.recoveredTasks()).sum() / recovered);
            summary.put(mode, values);
        }
        return summary;
    }

    public RunState state(String id) throws Exception { return repository.load(id); }
    public boolean busy(String id) { return active.contains(id); }

    public RunState feature(String text) throws Exception {
        ScenarioSpec spec = FeatureScenario.create(text);
        Path folder = root.resolve(".runs/requests");
        Files.createDirectories(folder);
        Path file = folder.resolve(UUID.randomUUID() + ".json");
        Files.writeString(file, Json.MAPPER.writeValueAsString(spec), StandardOpenOption.CREATE_NEW);
        return engine.start(file, "live");
    }

    public RunState scenario(String name, String mode) throws Exception {
        if (name == null || !SCENARIOS.contains(name)) throw new IllegalArgumentException("Unknown scenario");
        return engine.start(root.resolve("scenarios/" + name + "/scenario.json"), mode);
    }

    public synchronized void advance(String id) throws Exception {
        RunState state = state(id);
        if (state.pendingApprovalTask != null || state.pendingClarificationTask != null) {
            throw new IllegalStateException("Answer the pending review or clarification first");
        }
        if (Set.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.SAFE_STOPPED, RunStatus.NOT_APPROVED).contains(state.status)) {
            throw new IllegalStateException("This run has ended; start a new run");
        }
        if (active.size() >= 2) throw new IllegalStateException("Two runs are active; wait for capacity before starting another");
        if (!active.add(id)) throw new IllegalStateException("This run is already active");
        errors.remove(id);
        workers.submit(() -> {
            try { engine.advance(id); }
            catch (Exception failure) { errors.put(id, "Advance failed: " + failure.getClass().getSimpleName() + ". Inspect the audit and retry after resolving the issue."); }
            finally { active.remove(id); }
        });
    }

    public void approve(String id, String hash) throws Exception {
        engine.approve(id, hash, true);
        advance(id);
    }
    public void reject(String id, String hash) throws Exception {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Review the current proposal before rejecting it");
        engine.approve(id, hash, false);
    }
    public void clarify(String id, String answer) throws Exception {
        if (answer == null || answer.isBlank() || answer.length() > 8000) throw new IllegalArgumentException("Enter an answer in 1–8000 characters");
        engine.clarify(id, answer);
        advance(id);
    }
    public void revise(String id, String task, String feedback) throws Exception {
        if (feedback == null || feedback.isBlank()) throw new IllegalArgumentException("Explain the changes you want");
        engine.revise(id, task, feedback);
        advance(id);
    }

    public Map<String, Object> detail(String id) throws Exception {
        RunState state = state(id);
        ScenarioSpec spec = Json.MAPPER.readValue(Files.readString(ScenarioFiles.resolve(Path.of(state.specPath))), ScenarioSpec.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state);
        result.put("requirement", spec.requirement());
        result.put("tasks", spec.tasks());
        result.put("busy", busy(id));
        result.put("error", errors.getOrDefault(id, ""));
        var events = repository.events(id);
        result.put("events", events);
        long sequence = events.isEmpty() ? 0 : ((Number) events.getFirst().get("sequence")).longValue();
        var inspection = inspections.get(id, sequence, (head, at) -> new RunInspectionCache.Inspection(
            head, at, repository.auditValid(id), repository.timeline(id)));
        result.put("metrics", RunMetrics.from(state, inspection.timeline(), java.time.Instant.now()));
        result.put("auditValid", inspection.auditValid());
        result.put("auditCheckedAt", inspection.checkedAt());
        List<String> artifacts = new ArrayList<>();
        Path folder = root.resolve("evidence").resolve(state.id);
        if (Files.isDirectory(folder)) {
            try (var files = Files.list(folder)) {
                artifacts = files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
            }
        }
        result.put("artifacts", artifacts);
        List<Map<String, String>> clarification = new ArrayList<>();
        List<Map<String, String>> validation = new ArrayList<>();
        for (var task : spec.tasks()) {
            if (task.id().equals(state.pendingClarificationTask)) {
                for (String dependency : task.dependsOn()) {
                    Integer version = state.artifactVersions.get(dependency);
                    if (version != null) clarification.add(Map.of("task", dependency,
                        "text", artifact(id, dependency + "-v" + version + ".txt")));
                }
            }
            if ((task.kind() == TaskKind.VALIDATE || task.kind() == TaskKind.VALIDATE_RED) && state.tasks.get(task.id()) == TaskStatus.DONE) {
                Integer version = state.artifactVersions.get(task.id());
                if (version != null) validation.add(Map.of("task", task.id(),
                    "text", artifact(id, task.id() + "-v" + version + ".txt")));
            }
        }
        result.put("clarificationContext", clarification);
        result.put("validationEvidence", validation);
        if (state.status == RunStatus.PAUSED && state.pendingApprovalTask == null && state.pendingClarificationTask == null) {
            var retry = events.stream().filter(event -> "RETRY_AVAILABLE".equals(event.get("type"))).findFirst();
            if (retry.isPresent()) {
                String detail = String.valueOf(retry.get().get("detail"));
                result.put("retryReason", detail);
                String[] parts = detail.split(":", 2);
                if (parts.length == 2 && artifacts.contains(parts[1] + ".txt")) {
                    result.put("retryReason", detail + "\n" + artifact(id, parts[1] + ".txt"));
                }
            }
        }
        if (state.pendingApprovalTask != null) {
            String name = state.pendingApprovalTask + "-v" + state.artifactVersions.get(state.pendingApprovalTask) + ".txt";
            result.put("review", Map.of("task", state.pendingApprovalTask, "hash", state.pendingApprovalHash,
                "artifact", name, "patch", artifact(id, name), "baseline", state.baselineCommit));
        }
        return result;
    }

    public String artifact(String id, String name) throws Exception {
        UUID.fromString(id);
        if (name == null || !name.matches("[a-z0-9-]+\\.txt")) throw new IllegalArgumentException("Invalid artifact name");
        Path folder = root.resolve("evidence").resolve(id);
        Path file = folder.resolve(name);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) throw new IllegalArgumentException("Artifact not found");
        return Files.readString(file);
    }

    @Override public void close() { workers.shutdown(); }
}
