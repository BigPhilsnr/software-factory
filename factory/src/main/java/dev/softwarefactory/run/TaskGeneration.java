package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.generation.AgentRuntimes;
import dev.softwarefactory.generation.FixtureRuntime;
import dev.softwarefactory.generation.PromptBuilder;
import dev.softwarefactory.generation.SourceContext;
import dev.softwarefactory.governance.PolicyViolationException;
import dev.softwarefactory.scenario.ScenarioFiles;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.nio.file.Path;

/** Hands a task to an agent: a recorded fixture in fixture mode, the live model within the run's budget otherwise. */
final class TaskGeneration {
    private final RunStore runs;
    private final RunEvidence evidence;
    private final AgentRuntimes agents;

    TaskGeneration(RunStore runs, RunEvidence evidence, AgentRuntimes agents) {
        this.runs = runs;
        this.evidence = evidence;
        this.agents = agents;
    }

    /** Captures the task's inputs. Must not run concurrently with changes to the same run state. */
    Generation prepare(RunState state, TaskSpec task, ScenarioSpec spec) throws IOException {
        Path folder = ScenarioFiles.resolve(Path.of(state.specPath)).getParent();
        Path candidate = Path.of(state.candidatePath);
        if (RunMode.FIXTURE.equals(state.mode)) {
            return new Generation(task.role(), task.prompt(), task.fixture(), folder, candidate);
        }
        synchronized (state) {
            PromptBuilder prompt = new PromptBuilder(spec.requirement(), task)
                    .feedback(state.reviewFeedback.get(task.id()))
                    .previousFailure(evidence.latestDiagnostic(state, task.id()));
            for (String dependency : task.dependsOn()) {
                if (state.artifactVersions.containsKey(dependency)) {
                    prompt.inputArtifact(
                            dependency,
                            state.artifactHashes.get(dependency),
                            evidence.readCurrentOutput(state, dependency));
                }
            }
            prompt.repositorySource(SourceContext.read(candidate));
            return new Generation(task.role(), prompt.build(), null, folder, candidate);
        }
    }

    /** Only reads the immutable snapshot and synchronizes on the state for budget updates. */
    String generate(RunState state, TaskSpec task, Generation generation) throws IOException {
        if (RunMode.FIXTURE.equals(state.mode)) {
            if (generation.fixture() == null) throw new IllegalArgumentException("Fixture missing: " + task.id());
            Path file =
                    generation.scenarioFolder().resolve(generation.fixture()).normalize();
            if (!file.startsWith(generation.scenarioFolder()))
                throw new PolicyViolationException("Fixture escapes scenario directory");
            return new FixtureRuntime(file).generate(generation.role(), generation.prompt());
        }
        return agents.live(generation.candidate(), () -> reserveModelCall(state, task), (event, detail) -> {
                    synchronized (state) {
                        runs.record(state, event, task.id() + ":" + detail);
                    }
                })
                .generate(generation.role(), generation.prompt());
    }

    private void reserveModelCall(RunState state, TaskSpec task) throws IOException {
        synchronized (state) {
            if (state.modelCalls >= state.maxModelCalls) throw new SecurityException("Model call budget exhausted");
            state.modelCalls++;
            runs.record(
                    state,
                    EventTypes.MODEL_CALL_STARTED,
                    task.id() + ":" + state.modelCalls + "/" + state.maxModelCalls);
        }
    }
}
