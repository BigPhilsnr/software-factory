package dev.softwarefactory.run;

import static org.junit.jupiter.api.Assertions.*;

import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.generation.AgentRuntimes;
import dev.softwarefactory.platform.Json;
import dev.softwarefactory.scenario.FeatureScenario;
import dev.softwarefactory.scenario.ScenarioSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real engine and Git, scripted model: scope confirmation precedes design and code. */
class FeatureRequestGovernanceTest {
    @TempDir
    Path root;

    @Test
    void pinsCurrentCommittedCodeAndPausesBeforeDesignUntilTheOperatorConfirmsScope() throws Exception {
        var fixture = new RunEngineFixture(root);
        Files.writeString(root.resolve("README.md"), "current committed product\n");
        fixture.git("add", "README.md");
        fixture.git(
                "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "current product");
        String baseline = new GitWorkspace(root).resolveCommit("HEAD");
        Files.writeString(root.resolve("README.md"), "uncommitted work must stay private\n");
        var roles = new CopyOnWriteArrayList<String>();
        var prompts = new CopyOnWriteArrayList<String>();
        AgentRuntimes agents = (checkout, beforeRequest, audit) -> (role, prompt) -> {
            roles.add(role);
            prompts.add(prompt);
            return "AC-1: Keep existing redirects. Question: What is the retention period?";
        };
        var engine = fixture.engine(fixture.store, RunEngineFixture.LIVE_ENVIRONMENT, agents);
        var feature = FeatureScenario.create("Add expiration; agree retention before implementation.");
        // Keep the real requirements, clarification and parallel design branches; no synthetic patch is needed.
        var scenario = new ScenarioSpec(
                feature.id(),
                feature.requirement(),
                feature.baselineTag(),
                feature.tasks().subList(0, 4));
        Files.writeString(fixture.scenarioFile(), Json.MAPPER.writeValueAsString(scenario));
        var state = engine.start(fixture.scenarioFile(), RunMode.LIVE);
        assertEquals(baseline, state.baselineCommit);
        assertEquals("current committed product\n", fixture.candidateFile(state, "README.md"));
        state = engine.advance(state.id);
        assertEquals(RunStatus.PAUSED, state.status);
        assertEquals("clarify", state.pendingClarificationTask);
        assertEquals(List.of("requirements"), roles);
        assertEquals(TaskStatus.PENDING, state.tasks.get("architecture"));
        assertEquals(TaskStatus.PENDING, state.tasks.get("risk"));
        engine.advance(state.id);
        assertEquals(1, roles.size(), "Resuming must not bypass scope confirmation");
        engine.clarify(state.id, "Retention is 24 hours; preserve redirects until expiry.");
        state = engine.advance(state.id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertTrue(fixture.store.has("PARALLEL_JOIN"));
        assertEquals(3, roles.size());
        for (String prompt : prompts.subList(1, 3)) {
            assertTrue(prompt.contains("AC-1"));
            assertTrue(prompt.contains("Retention is 24 hours"));
        }
        fixture.git("add", "README.md");
        fixture.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "later product");
        assertNotEquals(baseline, new GitWorkspace(root).resolveCommit("HEAD"));
        assertEquals(baseline, fixture.store.load(state.id).baselineCommit);
        assertEquals("current committed product\n", fixture.candidateFile(state, "README.md"));
    }
}
