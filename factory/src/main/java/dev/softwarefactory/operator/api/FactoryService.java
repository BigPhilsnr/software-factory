package dev.softwarefactory.operator.api;

import dev.softwarefactory.generation.AgentRuntime;
import dev.softwarefactory.generation.AgentRuntimes;
import dev.softwarefactory.generation.ModelClients;
import dev.softwarefactory.platform.FactorySettings;
import dev.softwarefactory.platform.Json;
import dev.softwarefactory.platform.WorkflowConflictException;
import dev.softwarefactory.run.RunEngine;
import dev.softwarefactory.run.RunMode;
import dev.softwarefactory.run.RunState;
import dev.softwarefactory.scenario.FeatureScenario;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.validation.Preflight;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared browser/chat application service: validates operator actions, hands advancing to background
 * workers and serves what the operator sees. Database leases remain the transition authority.
 */
public final class FactoryService implements AutoCloseable {
    private final Path root;
    private final ControlRecords records;
    private final RunEngine engine;
    private final AgentRuntime chatRuntime;
    private final RunViews views;
    private final RunWorkers workers;

    public FactoryService(Path root, ControlRecords records, FactorySettings settings, ModelClients clients) {
        this(root, records, settings, clients, ConcurrentHashMap.newKeySet());
    }

    FactoryService(
            Path root, ControlRecords records, FactorySettings settings, ModelClients clients, Set<String> active) {
        this(
                root,
                records,
                settings,
                new RunEngine(records.runs(), root.toAbsolutePath().normalize(), settings, clients),
                chatRuntime(root.toAbsolutePath().normalize(), records, settings, clients),
                active);
    }

    FactoryService(
            Path root,
            ControlRecords records,
            FactorySettings settings,
            RunEngine engine,
            AgentRuntime chatRuntime,
            Set<String> active) {
        this.root = root.toAbsolutePath().normalize();
        this.records = records;
        this.engine = engine;
        this.chatRuntime = chatRuntime;
        this.views = new RunViews(this.root, records);
        this.workers = new RunWorkers(active);
    }

    /** Conversational generation against the checkout, charged to the persistent daily chat budget. */
    private static AgentRuntime chatRuntime(
            Path root, ControlRecords records, FactorySettings settings, ModelClients clients) {
        AgentRuntimes agents = AgentRuntimes.claude(clients, settings);
        return (role, prompt) -> agents.live(
                        root,
                        () -> records.chat().reserveRequest(settings.chatDailyRequests()),
                        (type, detail) -> records.chat().record(type, detail))
                .generate(role, prompt);
    }

    /** The checkout that owns scenarios, candidates and evidence. */
    public Path workspaceRoot() {
        return root;
    }

    public AgentRuntime chatRuntime() {
        return chatRuntime;
    }

    public Preflight validatorStatus() {
        return engine.validatorStatus();
    }

    /** Removes validator containers orphaned by crashed factory processes (or by this one, at shutdown). */
    public int sweepOrphanedValidatorContainers(boolean includeOwn) {
        return engine.sweepOrphanedValidatorContainers(includeOwn);
    }

    public List<RunState> runs() throws IOException {
        return views.runs();
    }

    public Map<String, Object> metrics() throws IOException {
        return views.metrics();
    }

    public RunState state(String id) throws IOException {
        return records.runs().load(id);
    }

    public boolean busy(String id) {
        return workers.busy(id);
    }

    public Map<String, Object> detail(String id) throws IOException {
        return views.detail(state(id), workers.busy(id), workers.lastError(id));
    }

    public String artifact(String id, String name) throws IOException {
        return views.artifact(id, name);
    }

    /** Saves a feature request as a live run; nothing is generated until it is advanced. */
    public RunState feature(String text) throws IOException, InterruptedException {
        ScenarioSpec spec = FeatureScenario.create(text);
        Path folder = root.resolve(".runs/requests");
        Files.createDirectories(folder);
        Path file = folder.resolve(UUID.randomUUID() + ".json");
        Files.writeString(file, Json.MAPPER.writeValueAsString(spec), StandardOpenOption.CREATE_NEW);
        return engine.start(file, RunMode.LIVE);
    }

    public RunState scenario(String name, String mode) throws IOException, InterruptedException {
        OperatorInput.requireScenario(name);
        return engine.start(root.resolve("scenarios/" + name + "/scenario.json"), mode);
    }

    /** Starts advancing on a background worker; progress is observed through {@link #detail}. */
    public synchronized void advance(String id) throws IOException {
        RunState state = state(id);
        requireAdvanceable(state);
        workers.requireCapacity(id);
        requireValidator(state);
        workers.start(id, () -> engine.advance(id));
    }

    public synchronized void approve(String id, String hash) throws IOException, InterruptedException {
        workers.requireCapacity(id);
        engine.approve(id, hash, true);
        advance(id);
    }

    public synchronized void reject(String id, String hash) throws IOException, InterruptedException {
        OperatorInput.requireReviewedHash(hash);
        workers.requireCapacity(id);
        engine.approve(id, hash, false);
    }

    public synchronized void clarify(String id, String answer) throws IOException, InterruptedException {
        OperatorInput.requireAnswer(answer);
        workers.requireCapacity(id);
        engine.clarify(id, answer);
        advance(id);
    }

    public synchronized void revise(String id, String task, String feedback) throws IOException, InterruptedException {
        OperatorInput.requireFeedback(feedback);
        workers.requireCapacity(id);
        engine.revise(id, task, feedback);
        advance(id);
    }

    /** Not synchronized: mutators fail fast with "shutting down" instead of waiting behind the worker drain. */
    @Override
    public void close() {
        try {
            workers.close();
        } finally {
            sweepOrphanedValidatorContainers(true);
        }
    }

    private static void requireAdvanceable(RunState state) {
        if (state.pendingApprovalTask != null || state.pendingClarificationTask != null) {
            throw new WorkflowConflictException("Answer the pending review or clarification first");
        }
        if (state.status.isTerminal()) throw new WorkflowConflictException("This run has ended; start a new run");
        if (state.revisionRequiredTask != null) {
            throw new WorkflowConflictException(
                    "Validation failed for this exact candidate; request changes to an upstream patch");
        }
    }

    /** Fail fast with 503 when pending validation cannot run; the engine enforces this again per task. */
    private void requireValidator(RunState state) throws IOException {
        if (RunEngine.unfinishedValidations(state, RunViews.scenario(state)).isEmpty()) return;
        Preflight status = engine.validatorStatus();
        if (!status.ready()) throw new ServiceUnavailableException(status.detail());
    }
}
