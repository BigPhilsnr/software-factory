package com.example.factory.web;

import com.example.factory.domain.*;
import com.example.factory.infra.*;
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

    public FactoryService(Path root, ControlRepository repository) {
        this.root = root.toAbsolutePath().normalize();
        this.repository = repository;
        this.engine = new RunEngine(repository, this.root);
    }

    ChatConversation conversation() {
        return new ChatConversation(root, (role, prompt) -> new AdkClaudeRuntime(
            System.getenv().getOrDefault("CLAUDE_MODEL", "claude-sonnet-4-5")).generate(role, prompt));
    }

    public List<RunState> runs() throws Exception { return repository.recentRuns(); }
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
        return engine.start(root.resolve("scenarios/" + name + ".json"), mode);
    }

    public synchronized void advance(String id) throws Exception {
        RunState state = state(id);
        if (state.pendingApprovalTask != null || state.pendingClarificationTask != null) {
            throw new IllegalStateException("Answer the pending review or clarification first");
        }
        if (Set.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.SAFE_STOPPED, RunStatus.NOT_APPROVED).contains(state.status)) {
            throw new IllegalStateException("This run has ended; start a new run");
        }
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
        ScenarioSpec spec = Json.MAPPER.readValue(Files.readString(Path.of(state.specPath)), ScenarioSpec.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state);
        result.put("requirement", spec.requirement());
        result.put("tasks", spec.tasks());
        result.put("busy", busy(id));
        result.put("error", errors.getOrDefault(id, ""));
        result.put("events", repository.events(id));
        result.put("auditValid", repository.auditValid(id));
        List<String> artifacts = new ArrayList<>();
        Path folder = root.resolve("evidence").resolve(state.id);
        if (Files.isDirectory(folder)) {
            try (var files = Files.list(folder)) {
                artifacts = files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
            }
        }
        result.put("artifacts", artifacts);
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
