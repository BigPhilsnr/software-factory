package dev.softwarefactory.run;

import dev.softwarefactory.audit.EvidenceStore;
import dev.softwarefactory.candidate.CommandRunner;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.generation.AgentRuntimes;
import dev.softwarefactory.platform.FactorySettings;
import dev.softwarefactory.platform.Json;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.Stage;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A real Git repository, evidence store and engine in a temporary directory, with an in-memory run store,
 * a scripted validator and a fixed clock: the whole workflow without Docker, a database or a provider.
 */
final class RunEngineFixture {
    static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    static final String BASELINE = "test-baseline";
    static final String PATCH = """
        diff --git a/README.md b/README.md
        --- a/README.md
        +++ b/README.md
        @@ -1 +1 @@
        -original
        +reviewed change
        """;
    /** Settings under which live runs may start. */
    static final Map<String, String> LIVE_ENVIRONMENT =
            Map.of("ANTHROPIC_API_KEY", "test-key", "FACTORY_AUDIT_KEY", "unit-test-audit-key-0123456789abcdef");

    final Path root;
    final InMemoryRunStore store = new InMemoryRunStore();
    final ScriptedValidator validator = new ScriptedValidator();

    RunEngineFixture(Path root) throws IOException, InterruptedException {
        this.root = root;
        Files.writeString(root.resolve("README.md"), "original\n");
        git("init", "-q");
        git("add", "README.md");
        git("-c", "user.name=Workflow Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "baseline");
        git("tag", BASELINE);
        Files.createDirectories(root.resolve("scenario"));
    }

    /** A fixture-mode engine over the in-memory store. */
    RunEngine engine() {
        return engine(store, Map.of(), unusedAgents());
    }

    RunEngine engine(RunStore runs, Map<String, String> environment, AgentRuntimes agents) {
        return new RunEngine(
                runs,
                new EvidenceStore(root.resolve("evidence")),
                new GitWorkspace(root),
                validator,
                agents,
                FactorySettings.from(environment),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static AgentRuntimes unusedAgents() {
        return (checkout, beforeRequest, audit) -> {
            throw new AssertionError("Fixture runs must not call a live agent");
        };
    }

    /** Writes the scenario and starts a run of it in the given mode. */
    RunState start(RunEngine engine, String mode, List<TaskSpec> tasks) throws IOException, InterruptedException {
        writeScenario("Test governance", tasks);
        return engine.start(scenarioFile(), mode);
    }

    void writeScenario(String requirement, List<TaskSpec> tasks) throws IOException {
        Files.writeString(
                scenarioFile(),
                Json.MAPPER.writeValueAsString(new ScenarioSpec("workflow-test", requirement, BASELINE, tasks)));
    }

    Path scenarioFile() {
        return root.resolve("scenario/scenario.json");
    }

    void fixture(String name, String content) throws IOException {
        Files.writeString(root.resolve("scenario").resolve(name), content);
    }

    Path evidence(RunState state, String name) {
        return root.resolve("evidence").resolve(state.id).resolve(name + ".txt");
    }

    String candidateFile(RunState state, String name) throws IOException {
        return Files.readString(Path.of(state.candidatePath).resolve(name));
    }

    static TaskSpec artifact(String id, List<String> dependencies, String fixture) {
        return new TaskSpec(
                id,
                Stage.REQUIREMENTS,
                dependencies,
                TaskKind.ARTIFACT,
                "requirements",
                "Inspect",
                fixture,
                List.of(),
                List.of(),
                false);
    }

    static TaskSpec patch(String id, List<String> dependencies, String fixture, boolean requiresApproval) {
        return new TaskSpec(
                id,
                Stage.IMPLEMENTATION,
                dependencies,
                TaskKind.PATCH,
                "implementer",
                "Change README",
                fixture,
                List.of(),
                List.of("README.md"),
                requiresApproval);
    }

    static TaskSpec validate(String id, List<String> dependencies) {
        return new TaskSpec(
                id,
                Stage.VALIDATION,
                dependencies,
                TaskKind.VALIDATE,
                "validator",
                "Test",
                null,
                List.of(),
                List.of(),
                false);
    }

    static TaskSpec release(String id, List<String> dependencies) {
        return new TaskSpec(
                id,
                Stage.RELEASE,
                dependencies,
                TaskKind.RELEASE,
                "release",
                "Review",
                null,
                List.of(),
                List.of(),
                true);
    }

    static TaskSpec clarify(String id, List<String> dependencies) {
        return new TaskSpec(
                id,
                Stage.REQUIREMENTS,
                dependencies,
                TaskKind.CLARIFY,
                "operator",
                "Choose the scope",
                null,
                List.of(),
                List.of(),
                true);
    }

    void git(String... args) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        CommandRunner.checked(root, command, Duration.ofSeconds(10));
    }
}
