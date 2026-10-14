package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.audit.EvidenceStore;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.generation.AgentRuntimes;
import dev.softwarefactory.generation.ModelClients;
import dev.softwarefactory.platform.FactorySettings;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.TaskSpec;
import dev.softwarefactory.validation.CandidateValidator;
import dev.softwarefactory.validation.Preflight;
import dev.softwarefactory.validation.SandboxValidator;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.slf4j.MDC;

/**
 * Small single-process control plane, and the table of contents of a run's journey: {@link #start} a
 * scenario, {@link #advance} its task graph, and apply the operator's decisions ({@link #approve},
 * {@link #clarify}, {@link #revise}). Every transition holds the run's lease and is committed before the
 * next action.
 */
public final class RunEngine {
    static final String RUN_ID = "runId";
    static final String TASK_ID = "taskId";

    private final RunStore runs;
    private final CandidateValidator validator;
    private final Transitions transitions;

    /** Production wiring: evidence and candidates under the project root, Docker validation, Claude agents. */
    public RunEngine(RunStore runs, Path projectRoot, FactorySettings settings, ModelClients clients) {
        this(
                runs,
                new EvidenceStore(projectRoot.resolve("evidence")),
                new GitWorkspace(projectRoot),
                new SandboxValidator(settings.mavenRepository()),
                AgentRuntimes.claude(clients, settings),
                settings,
                Clock.systemUTC());
    }

    /** Every collaborator is supplied, so the engine can run without Docker, a database or a provider. */
    public RunEngine(
            RunStore runs,
            EvidenceStore evidenceStore,
            GitWorkspace workspace,
            CandidateValidator validator,
            AgentRuntimes agents,
            FactorySettings settings,
            Clock clock) {
        this.runs = runs;
        this.validator = validator;
        this.transitions = Transitions.wire(runs, evidenceStore, workspace, validator, agents, settings, clock);
    }

    @FunctionalInterface
    private interface Transition {
        RunState apply() throws IOException, InterruptedException;
    }

    /** Creates a run and its isolated candidate from a scenario file; nothing is generated yet. */
    public RunState start(Path scenarioFile, String mode) throws IOException, InterruptedException {
        return transitions.start().start(scenarioFile, mode);
    }

    /** Executes ready tasks until the run ends or must wait for the operator or the platform. */
    public RunState advance(String id) throws IOException, InterruptedException {
        return underLease(id, () -> transitions.advance().advance(id));
    }

    /** Grants (or, when not {@code accepted}, refuses) the pending approval for exactly {@code reviewedHash}. */
    public RunState approve(String id, String reviewedHash, boolean accepted) throws IOException, InterruptedException {
        return underLease(id, () -> transitions.approve().decide(id, reviewedHash, accepted));
    }

    /** Answers the pending clarification. */
    public RunState clarify(String id, String answer) throws IOException, InterruptedException {
        return underLease(id, () -> transitions.clarify().answer(id, answer));
    }

    /** Invalidates a task and everything downstream of it, optionally with feedback for the next attempt. */
    public RunState revise(String id, String taskId, String feedback) throws IOException, InterruptedException {
        ReviseRun.requireUsableFeedback(feedback);
        return underLease(id, () -> transitions.revise().revise(id, taskId, feedback));
    }

    /** Explicit operator action: raise a paused live run's total cap without spending calls or advancing it. */
    public RunState increaseBudget(String id, int total) throws IOException, InterruptedException {
        return underLease(id, () -> {
            RunState state = runs.load(id);
            String legacyTask = legacyBudgetTask(state);
            if ((state.status != RunStatus.PAUSED && legacyTask == null) || !RunMode.LIVE.equals(state.mode))
                throw new IllegalArgumentException(
                        "Only paused live runs or verified legacy budget stops can receive additional budget");
            if (!runs.auditValid(id)) throw new IllegalArgumentException("Audit verification failed");
            if (total <= state.maxModelCalls || total > FactorySettings.MAX_MODEL_CALLS_LIMIT)
                throw new IllegalArgumentException("New total must exceed the existing cap and be at most "
                        + FactorySettings.MAX_MODEL_CALLS_LIMIT);
            if (legacyTask != null) {
                state.tasks.put(legacyTask, TaskStatus.PENDING);
                state.status = RunStatus.PAUSED;
                state.finishedAt = null;
            }
            int previous = state.maxModelCalls;
            state.maxModelCalls = total;
            runs.record(
                    state,
                    EventTypes.MODEL_BUDGET_INCREASED,
                    previous + "->" + total
                            + (legacyTask == null ? "" : "; recovered legacy budget stop:" + legacyTask));
            return state;
        });
    }

    /** Only the exact old budget-exhaustion event is recoverable; other terminal states remain final. */
    private String legacyBudgetTask(RunState state) throws IOException {
        if (state.status != RunStatus.SAFE_STOPPED || state.modelCalls < state.maxModelCalls) return null;
        var event = runs.latestEvent(state.id).orElse(null);
        if (event == null || !EventTypes.POLICY_SAFE_STOP.equals(event.type())) return null;
        var failed = state.tasks.entrySet().stream()
                .filter(entry -> entry.getValue() == TaskStatus.FAILED)
                .toList();
        if (failed.size() != 1) return null;
        String task = failed.getFirst().getKey();
        return (task + ":Model call budget exhausted").equals(event.detail()) ? task : null;
    }

    /** Repairs only stored hunk counts and requests a new exact-hash review. */
    public RunState recoverPatch(String id, String task, int version, String proposalHash)
            throws IOException, InterruptedException {
        return underLease(id, () -> transitions.recoverPatch().recover(id, task, version, proposalHash));
    }

    public Preflight validatorStatus() {
        return validator.preflight();
    }

    public int sweepOrphanedValidatorContainers(boolean includeOwn) {
        return validator.sweepOrphanedContainers(includeOwn);
    }

    /** Validation tasks of this run that have not passed yet; they need Docker and the validator image. */
    public static List<String> unfinishedValidations(RunState state, ScenarioSpec spec) {
        return spec.tasks().stream()
                .filter(task -> task.kind().isValidation() && state.tasks.get(task.id()) != TaskStatus.DONE)
                .map(TaskSpec::id)
                .toList();
    }

    /** Only the lease holder may transition a run; a second caller gets a conflict instead of waiting. */
    private RunState underLease(String id, Transition transition) throws IOException, InterruptedException {
        try (var ignoredContext = MDC.putCloseable(RUN_ID, id);
                var ignoredLease = runs.lease(id)) {
            return transition.apply();
        }
    }
}
