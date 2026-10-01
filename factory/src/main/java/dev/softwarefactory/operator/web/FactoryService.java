package dev.softwarefactory.operator.web;

import dev.softwarefactory.agents.AdkClaudeRuntime;
import dev.softwarefactory.agents.ModelClients;
import dev.softwarefactory.configuration.FactorySettings;
import dev.softwarefactory.execution.SandboxValidator;
import dev.softwarefactory.observability.RunMetrics;
import dev.softwarefactory.persistence.ControlRepository;
import dev.softwarefactory.serialization.Json;
import dev.softwarefactory.workflow.RunEngine;
import dev.softwarefactory.workflow.RunState;
import dev.softwarefactory.workflow.RunStatus;
import dev.softwarefactory.workflow.TaskKind;
import dev.softwarefactory.workflow.TaskStatus;
import dev.softwarefactory.workflow.WorkflowConflictException;
import dev.softwarefactory.workflow.scenario.ScenarioFiles;
import dev.softwarefactory.workflow.scenario.ScenarioSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Shared browser/chat application service. Database leases remain the transition authority. */
public final class FactoryService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(FactoryService.class);
    private static final Set<String> SCENARIOS = Set.of("greenfield", "brownfield", "ambiguous", "bugfix");
    private static final Set<RunStatus> TERMINAL =
            Set.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.SAFE_STOPPED, RunStatus.NOT_APPROVED);
    /** Pause reasons shown to the operator, newest first. */
    private static final Set<String> PAUSE_EVENTS =
            Set.of("RETRY_AVAILABLE", "INFRASTRUCTURE_UNAVAILABLE", "REVISION_REQUIRED");

    private static final int MAX_ACTIVE_RUNS = 2;
    private static final int MAX_ANSWER = 8000;
    private static final Duration DRAIN = Duration.ofSeconds(30);
    private static final Duration FORCED_STOP = Duration.ofSeconds(5);
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern ARTIFACT_NAME = Pattern.compile("[a-z0-9-]+\\.txt");
    private final Path root;
    private final ControlRepository repository;
    private final FactorySettings settings;
    private final ModelClients clients;
    private final RunEngine engine;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> active;
    private final Map<String, String> errors = LruMap.create(LruMap.DEFAULT_CAPACITY);
    private final RunInspectionCache inspections = new RunInspectionCache(Clock.systemUTC());

    public FactoryService(Path root, ControlRepository repository, FactorySettings settings, ModelClients clients) {
        this(root, repository, settings, clients, ConcurrentHashMap.newKeySet());
    }

    FactoryService(
            Path root,
            ControlRepository repository,
            FactorySettings settings,
            ModelClients clients,
            Set<String> active) {
        this.active = active;
        this.root = root.toAbsolutePath().normalize();
        this.repository = repository;
        this.settings = settings;
        this.clients = clients;
        this.engine = new RunEngine(repository, this.root, settings, clients);
    }

    ChatConversation conversation() {
        return new ChatConversation(
                root,
                (role, prompt) -> new AdkClaudeRuntime(
                                clients,
                                settings,
                                root,
                                () -> repository.reserveChatRequest(settings.chatDailyRequests()),
                                repository::chatAudit)
                        .generate(role, prompt));
    }

    FactorySettings settings() {
        return settings;
    }

    SandboxValidator.Preflight validatorStatus() {
        return engine.validatorStatus();
    }

    /** Removes validator containers orphaned by crashed factory processes (or by this one, at shutdown). */
    int removeOrphanedValidatorContainers(boolean includeOwn) {
        return engine.removeOrphanedValidatorContainers(includeOwn);
    }

    public List<RunState> runs() throws IOException {
        return repository.recentRuns();
    }

    public Map<String, Object> metrics() throws IOException {
        List<RunState> runs = runs();
        Map<String, List<ControlRepository.AuditEvent>> timelines = repository.recentTimelines(RunMetrics.EVENT_TYPES);
        Instant now = Instant.now();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put(
                "sample",
                "Latest " + ControlRepository.RECENT_RUNS + " updated runs; fixture and live outcomes are separate");
        for (String mode : List.of("fixture", "live")) {
            List<RunState> sample =
                    runs.stream().filter(run -> mode.equals(run.mode)).toList();
            long ended = sample.stream().filter(run -> run.finishedAt != null).count();
            long completed = sample.stream()
                    .filter(run -> run.status == RunStatus.COMPLETED)
                    .count();
            List<RunMetrics> measurements = sample.stream()
                    .map(run -> RunMetrics.from(run, timelines.getOrDefault(run.id, List.of()), now))
                    .toList();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("runs", sample.size());
            values.put("terminalRuns", ended);
            values.put("completedRuns", completed);
            values.put("completionRate", ended == 0 ? null : (double) completed / ended);
            values.put(
                    "outcomes",
                    sample.stream()
                            .collect(Collectors.groupingBy(run -> run.status.toString(), Collectors.counting())));
            values.put(
                    "retryExecutions",
                    measurements.stream().mapToInt(RunMetrics::retryExecutions).sum());
            values.put(
                    "rollbacks",
                    measurements.stream().mapToInt(RunMetrics::rollbacks).sum());
            values.put(
                    "retryRunRate",
                    sample.isEmpty()
                            ? null
                            : (double) measurements.stream()
                                            .filter(m -> m.retryExecutions() > 0)
                                            .count()
                                    / sample.size());
            values.put(
                    "rollbackRunRate",
                    sample.isEmpty()
                            ? null
                            : (double) measurements.stream()
                                            .filter(m -> m.rollbacks() > 0)
                                            .count()
                                    / sample.size());
            values.put(
                    "meanTerminalLatencyMillis",
                    ended == 0
                            ? null
                            : measurements.stream()
                                    .filter(RunMetrics::terminal)
                                    .mapToLong(RunMetrics::elapsedMillis)
                                    .average()
                                    .orElseThrow());
            int recovered =
                    measurements.stream().mapToInt(RunMetrics::recoveredTasks).sum();
            values.put("recoveredTasks", recovered);
            values.put(
                    "meanRecoveryMillis",
                    recovered == 0
                            ? null
                            : measurements.stream()
                                            .filter(m -> m.meanRecoveryMillis() != null)
                                            .mapToLong(m -> m.meanRecoveryMillis() * m.recoveredTasks())
                                            .sum()
                                    / recovered);
            summary.put(mode, values);
        }
        return summary;
    }

    public RunState state(String id) throws IOException {
        return repository.load(id);
    }

    public boolean busy(String id) {
        return active.contains(id);
    }

    public RunState feature(String text) throws IOException, InterruptedException {
        ScenarioSpec spec = FeatureScenario.create(text);
        Path folder = root.resolve(".runs/requests");
        Files.createDirectories(folder);
        Path file = folder.resolve(UUID.randomUUID() + ".json");
        Files.writeString(file, Json.MAPPER.writeValueAsString(spec), StandardOpenOption.CREATE_NEW);
        return engine.start(file, "live");
    }

    public RunState scenario(String name, String mode) throws IOException, InterruptedException {
        if (name == null || !SCENARIOS.contains(name)) throw new IllegalArgumentException("Unknown scenario");
        return engine.start(root.resolve("scenarios/" + name + "/scenario.json"), mode);
    }

    public synchronized void advance(String id) throws IOException {
        RunState state = state(id);
        if (state.pendingApprovalTask != null || state.pendingClarificationTask != null) {
            throw new WorkflowConflictException("Answer the pending review or clarification first");
        }
        if (TERMINAL.contains(state.status)) throw new WorkflowConflictException("This run has ended; start a new run");
        if (state.revisionRequiredTask != null) {
            throw new WorkflowConflictException(
                    "Validation failed for this exact candidate; request changes to an upstream patch");
        }
        requireCapacity(id);
        requireValidator(state);
        if (!active.add(id)) throw new WorkflowConflictException("This run is already active");
        errors.remove(id);
        workers.submit(() -> {
            try (var context = MDC.putCloseable("runId", id)) {
                engine.advance(id);
            } catch (Exception failure) {
                LOG.error("Advance of run {} failed", id, failure);
                errors.put(
                        id,
                        "Advance failed: " + failure.getClass().getSimpleName()
                                + ". Inspect the audit and retry after resolving the issue.");
            } finally {
                active.remove(id);
            }
        });
    }

    /** Fail fast with 503 when pending validation cannot run; the engine enforces this again per task. */
    private void requireValidator(RunState state) throws IOException {
        ScenarioSpec spec = readSpec(state);
        if (RunEngine.unfinishedValidations(state, spec).isEmpty()) return;
        SandboxValidator.Preflight status = engine.validatorStatus();
        if (!status.ready()) throw new ServiceUnavailableException(status.detail());
    }

    public synchronized void approve(String id, String hash) throws Exception {
        requireCapacity(id);
        engine.approve(id, hash, true);
        advance(id);
    }

    public synchronized void reject(String id, String hash) throws Exception {
        if (hash == null || !HASH.matcher(hash).matches())
            throw new IllegalArgumentException("Review the current proposal before rejecting it");
        requireCapacity(id);
        engine.approve(id, hash, false);
    }

    public synchronized void clarify(String id, String answer) throws Exception {
        if (answer == null || answer.isBlank() || answer.length() > MAX_ANSWER) {
            throw new IllegalArgumentException("Enter an answer in 1–" + MAX_ANSWER + " characters");
        }
        requireCapacity(id);
        engine.clarify(id, answer);
        advance(id);
    }

    public synchronized void revise(String id, String task, String feedback) throws Exception {
        if (feedback == null || feedback.isBlank()) throw new IllegalArgumentException("Explain the changes you want");
        requireCapacity(id);
        engine.revise(id, task, feedback);
        advance(id);
    }

    private void requireCapacity(String id) {
        if (workers.isShutdown())
            throw new ServiceUnavailableException("Factory is shutting down; no decision was recorded");
        if (active.contains(id)) throw new WorkflowConflictException("This run is already active");
        if (active.size() >= MAX_ACTIVE_RUNS) {
            throw new ServiceUnavailableException(
                    "Two runs are active; no decision was recorded. Retry when capacity is available.");
        }
    }

    private static ScenarioSpec readSpec(RunState state) throws IOException {
        try {
            return Json.MAPPER.readValue(
                    Files.readString(ScenarioFiles.resolve(Path.of(state.specPath))), ScenarioSpec.class);
        } catch (NoSuchFileException missing) {
            throw new NotFoundException("Scenario specification not found for run " + state.id);
        }
    }

    public Map<String, Object> detail(String id) throws Exception {
        RunState state = state(id);
        ScenarioSpec spec = readSpec(state);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state);
        result.put("requirement", spec.requirement());
        result.put("tasks", spec.tasks());
        result.put("busy", busy(id));
        result.put("error", errors.getOrDefault(id, ""));
        var events = repository.events(id);
        result.put("events", events);
        long sequence = events.isEmpty() ? 0 : ((Number) events.getFirst().get("sequence")).longValue();
        var inspection = inspections.get(
                id,
                sequence,
                (head, at) -> new RunInspectionCache.Inspection(
                        head, at, repository.auditValid(id), repository.timeline(id)));
        result.put("metrics", RunMetrics.from(state, inspection.timeline(), Instant.now()));
        result.put("auditValid", inspection.auditValid());
        result.put("auditCheckedAt", inspection.checkedAt());
        List<String> artifacts = artifactNames(state.id);
        result.put("artifacts", artifacts);
        List<Map<String, String>> clarification = new ArrayList<>();
        List<Map<String, String>> validation = new ArrayList<>();
        for (var task : spec.tasks()) {
            if (task.id().equals(state.pendingClarificationTask)) {
                for (String dependency : task.dependsOn()) {
                    Integer version = state.artifactVersions.get(dependency);
                    if (version != null)
                        clarification.add(Map.of(
                                "task", dependency, "text", displayArtifact(id, dependency + "-v" + version + ".txt")));
                }
            }
            if ((task.kind() == TaskKind.VALIDATE || task.kind() == TaskKind.VALIDATE_RED)
                    && state.tasks.get(task.id()) == TaskStatus.DONE) {
                Integer version = state.artifactVersions.get(task.id());
                if (version != null)
                    validation.add(Map.of(
                            "task", task.id(), "text", displayArtifact(id, task.id() + "-v" + version + ".txt")));
            }
        }
        result.put("clarificationContext", clarification);
        result.put("validationEvidence", validation);
        if (state.status == RunStatus.PAUSED
                && state.pendingApprovalTask == null
                && state.pendingClarificationTask == null) {
            events.stream()
                    .filter(event -> PAUSE_EVENTS.contains(String.valueOf(event.get("type"))))
                    .findFirst()
                    .ifPresent(event -> putPauseReason(result, id, artifacts, event));
        }
        if (state.pendingApprovalTask != null) {
            String name =
                    state.pendingApprovalTask + "-v" + state.artifactVersions.get(state.pendingApprovalTask) + ".txt";
            result.put(
                    "review",
                    Map.of(
                            "task",
                            state.pendingApprovalTask,
                            "hash",
                            state.pendingApprovalHash,
                            "artifact",
                            name,
                            "patch",
                            displayArtifact(id, name),
                            "baseline",
                            state.baselineCommit));
        }
        return result;
    }

    private List<String> artifactNames(String id) throws IOException {
        Path folder = root.resolve("evidence").resolve(id);
        if (!Files.isDirectory(folder)) return List.of();
        try (var files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    /** Exposes why a paused run stopped: retry offered, platform unavailable, or revision required. */
    private void putPauseReason(
            Map<String, Object> result, String id, List<String> artifacts, Map<String, Object> event) {
        String type = String.valueOf(event.get("type"));
        String detail = String.valueOf(event.get("detail"));
        String[] parts = detail.split(":", 2);
        String reason = parts.length == 2 && artifacts.contains(parts[1] + ".txt")
                ? detail + "\n" + displayArtifact(id, parts[1] + ".txt")
                : detail;
        result.put(
                "pauseKind",
                switch (type) {
                    case "INFRASTRUCTURE_UNAVAILABLE" -> "infrastructure";
                    case "REVISION_REQUIRED" -> "revision";
                    default -> "retry";
                });
        result.put("retryReason", reason);
    }

    private String displayArtifact(String id, String name) {
        try {
            return artifact(id, name);
        } catch (IOException | NotFoundException | IllegalArgumentException unavailable) {
            return "Evidence unavailable: " + name
                    + ". Approval still requires intact evidence. You can reject this run.";
        }
    }

    public String artifact(String id, String name) throws IOException {
        UUID.fromString(id);
        if (name == null || !ARTIFACT_NAME.matcher(name).matches())
            throw new IllegalArgumentException("Invalid artifact name");
        Path file = root.resolve("evidence").resolve(id).resolve(name);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file))
            throw new NotFoundException("Artifact not found: " + name);
        return Files.readString(file);
    }

    /**
     * Stops accepting work, then drains workers for a bounded time. Not synchronized: mutators fail fast
     * with "shutting down" instead of waiting behind the drain.
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
        } finally {
            removeOrphanedValidatorContainers(true);
        }
    }
}
