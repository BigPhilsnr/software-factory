package dev.softwarefactory.run;

import static org.junit.jupiter.api.Assertions.*;

import dev.softwarefactory.candidate.DraftWorkspace;
import dev.softwarefactory.generation.AgentRuntime;
import dev.softwarefactory.generation.AgentRuntimes;
import dev.softwarefactory.platform.GenerationUnavailableException;
import dev.softwarefactory.validation.ValidationFailedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchDraftingTest {
    @TempDir
    Path root;

    @FunctionalInterface
    interface Edit {
        void run(String prompt, DraftWorkspace draft) throws IOException, InterruptedException;
    }

    private static AgentRuntimes agents(Edit edit) {
        return (checkout, beforeRequest, audit) -> new AgentRuntime() {
            @Override
            public String generate(String role, String prompt) {
                throw new AssertionError("Must draft using tools");
            }

            @Override
            public void draft(String role, String prompt, DraftWorkspace draft)
                    throws IOException, InterruptedException {
                beforeRequest.reserve();
                edit.run(prompt, draft);
            }
        };
    }

    @Test
    void repairsValidationFeedbackThenRequiresApprovalForGitGeneratedDiff() throws Exception {
        var fixture = new RunEngineFixture(root);
        var calls = new AtomicInteger();
        var prompts = new ArrayList<String>();
        fixture.validator.answers(() -> {
            if (calls.get() == 1) throw new ValidationFailedException("Compiler: use corrected behavior");
            return "passed";
        });
        var engine = fixture.engine(fixture.store, RunEngineFixture.LIVE_ENVIRONMENT, agents((prompt, draft) -> {
            prompts.add(prompt);
            draft.replace(
                    "README.md",
                    calls.incrementAndGet() == 1 ? "original" : "first",
                    calls.get() == 1 ? "first" : "corrected");
        }));
        var state = fixture.start(
                engine,
                RunMode.LIVE,
                List.of(
                        RunEngineFixture.patch("apply", List.of(), null, true),
                        RunEngineFixture.validate("validate", List.of("apply"))));
        state = engine.advance(state.id);
        assertEquals(RunStatus.PAUSED, state.status);
        assertEquals("apply", state.pendingApprovalTask);
        assertEquals(2, calls.get());
        assertTrue(prompts.get(1).contains("Compiler: use corrected behavior"));
        assertEquals("original\n", fixture.candidateFile(state, "README.md"));
        assertTrue(Files.readString(fixture.evidence(state, "apply-v1")).contains("+corrected"));
        engine.approve(state.id, state.pendingApprovalHash, true);
        state = engine.advance(state.id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertEquals("corrected\n", fixture.candidateFile(state, "README.md"));
        assertEquals(2, state.modelCalls);
    }

    @Test
    void providerPauseKeepsDraftAcrossNewEngineAndSuppliesDiagnosticOnResume() throws Exception {
        var fixture = new RunEngineFixture(root);
        var first = fixture.engine(fixture.store, RunEngineFixture.LIVE_ENVIRONMENT, agents((prompt, draft) -> {
            draft.replace("README.md", "original", "saved progress");
            throw new GenerationUnavailableException("Output token limit reached");
        }));
        var state = fixture.start(first, RunMode.LIVE, List.of(RunEngineFixture.patch("apply", List.of(), null, true)));
        state = first.advance(state.id);
        assertEquals(RunStatus.PAUSED, state.status);
        assertTrue(state.attempts.isEmpty());
        String directory = state.drafting.get("apply").directory();
        var resumed = fixture.engine(fixture.store, RunEngineFixture.LIVE_ENVIRONMENT, agents((prompt, draft) -> {
            assertEquals(directory, draft.path().toString());
            assertTrue(prompt.contains("Output token limit reached"));
            assertTrue(draft.read("README.md").contains("saved progress"));
            draft.replace("README.md", "saved progress", "finished");
        }));
        state = resumed.advance(state.id);
        assertEquals("apply", state.pendingApprovalTask);
        assertEquals("original\n", fixture.candidateFile(state, "README.md"));
        assertEquals(2, state.modelCalls);
    }

    @Test
    void identicalFailedDraftStopsRepairAndNeverReachesApproval() throws Exception {
        var fixture = new RunEngineFixture(root);
        var calls = new AtomicInteger();
        fixture.validator.answers(() -> {
            throw new ValidationFailedException("Still broken");
        });
        var engine = fixture.engine(fixture.store, RunEngineFixture.LIVE_ENVIRONMENT, agents((prompt, draft) -> {
            if (calls.incrementAndGet() == 1) draft.replace("README.md", "original", "broken");
        }));
        var state = fixture.start(
                engine,
                RunMode.LIVE,
                List.of(
                        RunEngineFixture.patch("apply", List.of(), null, true),
                        RunEngineFixture.validate("validate", List.of("apply"))));
        state = engine.advance(state.id);
        assertEquals(2, calls.get());
        assertEquals(1, fixture.validator.requiredTestClasses.size(), "Unchanged failure must not rerun sandbox");
        assertEquals(RunStatus.PAUSED, state.status);
        assertNull(state.pendingApprovalTask);
        assertTrue(Files.readString(fixture.evidence(state, "apply-error-v1")).contains("no progress"));
        assertEquals("original\n", fixture.candidateFile(state, "README.md"));
    }
}
