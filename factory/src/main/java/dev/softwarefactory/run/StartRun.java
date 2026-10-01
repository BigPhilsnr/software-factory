package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.platform.FactorySettings;
import dev.softwarefactory.platform.LogText;
import dev.softwarefactory.platform.WorkflowConflictException;
import dev.softwarefactory.scenario.ScenarioFiles;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.TaskGraph;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a scenario into a run: validates its task graph, pins the baseline commit and the hashes of what
 * was asked, and creates the isolated candidate. Nothing is generated yet.
 */
final class StartRun {
    private static final Logger LOG = LoggerFactory.getLogger(StartRun.class);

    private final RunStore runs;
    private final GitWorkspace workspace;
    private final FactorySettings settings;
    private final Clock clock;

    StartRun(RunStore runs, GitWorkspace workspace, FactorySettings settings, Clock clock) {
        this.runs = runs;
        this.workspace = workspace;
        this.settings = settings;
        this.clock = clock;
    }

    RunState start(Path scenarioFile, String mode) throws IOException, InterruptedException {
        requireAvailable(mode);
        Path specPath = ScenarioFiles.resolve(scenarioFile);
        ScenarioFiles.Document scenario = ScenarioFiles.read(specPath);
        ScenarioSpec spec = requireRunnable(scenario.spec());
        RunState state = new RunState(UUID.randomUUID().toString(), spec.id(), Hashes.sha256(spec.requirement()));
        state.startedAt = clock.instant();
        state.specPath = specPath.toString();
        state.specHash = Hashes.sha256(scenario.text());
        state.baselineTag = spec.baselineTag();
        state.baselineCommit = workspace.resolveCommit(state.baselineTag);
        state.mode = mode;
        state.maxModelCalls = settings.maxModelCalls();
        state.candidatePath = workspace.create(state.id, state.baselineCommit).toString();
        for (TaskSpec task : spec.tasks()) state.tasks.put(task.id(), TaskStatus.PENDING);
        boolean created = false;
        try {
            runs.record(state, EventTypes.RUN_CREATED, mode + ":" + spec.id());
            created = true;
        } finally {
            if (!created) removeCandidateUnlessPersisted(state);
        }
        LOG.info(
                "Created {} run {} for scenario {}",
                LogText.singleLine(mode),
                LogText.singleLine(state.id),
                LogText.singleLine(spec.id()));
        return state;
    }

    private void requireAvailable(String mode) {
        if (!RunMode.isKnown(mode)) throw new IllegalArgumentException("Mode must be fixture or live");
        if (RunMode.LIVE.equals(mode) && !settings.liveReady())
            throw new WorkflowConflictException(settings.liveBlocker());
    }

    private static ScenarioSpec requireRunnable(ScenarioSpec spec) {
        new TaskGraph(spec.tasks());
        if (spec.requirement() == null || spec.requirement().isBlank() || spec.baselineTag() == null) {
            throw new IllegalArgumentException("Scenario must include requirement and baseline");
        }
        return spec;
    }

    /** A lost commit acknowledgement is ambiguous: never delete the candidate of a run that was persisted. */
    private void removeCandidateUnlessPersisted(RunState state) throws InterruptedException {
        try {
            runs.load(state.id);
        } catch (RunStore.MissingRunException notSaved) {
            try {
                workspace.removeOwned(Path.of(state.candidatePath));
            } catch (IOException cleanupFailure) {
                LOG.warn("Could not remove the candidate of unsaved run {}", state.id, cleanupFailure);
            }
        } catch (IOException uncertain) {
            LOG.warn("Could not confirm whether run {} was saved; keeping its candidate", state.id, uncertain);
        }
    }
}
