package dev.softwarefactory.run;

import static dev.softwarefactory.run.RunEngineFixture.PATCH;
import static dev.softwarefactory.run.RunEngineFixture.patch;
import static dev.softwarefactory.run.RunEngineFixture.validate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.softwarefactory.platform.InfrastructureException;
import dev.softwarefactory.scenario.Stage;
import dev.softwarefactory.scenario.TaskKind;
import dev.softwarefactory.scenario.TaskSpec;
import dev.softwarefactory.validation.ValidationFailedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** How validation outcomes steer a run, with the sandbox replaced by a scripted validator. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RunEngineValidationTest {
    private static final String TEST_PATCH = """
        diff --git a/shortener/src/test/java/dev/shortener/link/ExpiryTest.java b/shortener/src/test/java/dev/shortener/link/ExpiryTest.java
        new file mode 100644
        --- /dev/null
        +++ b/shortener/src/test/java/dev/shortener/link/ExpiryTest.java
        @@ -0,0 +1 @@
        +class ExpiryTest {}
        """;

    @TempDir
    Path root;

    private RunEngineFixture fixture;
    private InMemoryRunStore store;
    private ScriptedValidator validator;
    private RunEngine engine;

    @BeforeEach
    void setup() throws Exception {
        fixture = new RunEngineFixture(root);
        store = fixture.store;
        validator = fixture.validator;
        engine = fixture.engine();
        fixture.fixture("change.patch", PATCH);
    }

    private RunState patchedRun() throws Exception {
        return fixture.start(
                engine,
                RunMode.FIXTURE,
                List.of(patch("apply", List.of(), "change.patch", false), validate("validate", List.of("apply"))));
    }

    @Test
    void passingValidationCompletesTheRunAndPinsTheValidatedCandidate() throws Exception {
        RunState state = engine.advance(patchedRun().id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertEquals(RunEngineFixture.NOW, state.finishedAt);
        assertNotNull(state.validatedCandidateHash);
        assertEquals(List.of(state.validatedCandidateHash), store.details("CANDIDATE_VALIDATED"));
        assertTrue(Files.readString(fixture.evidence(state, "validate-v1")).contains("Sandboxed Maven tests passed"));
        assertEquals(List.of(Set.of()), validator.requiredTestClasses, "A README change adds no test classes");
    }

    @Test
    void deterministicValidationFailureRequiresRevisionInsteadOfARetry() throws Exception {
        validator.answers(() -> {
            throw new ValidationFailedException("Candidate tests failed:\nexpected 200 but was 500");
        });
        RunState state = engine.advance(patchedRun().id);
        assertEquals(RunStatus.PAUSED, state.status);
        assertEquals("validate", state.revisionRequiredTask);
        assertEquals(TaskStatus.FAILED, state.tasks.get("validate"));
        assertTrue(state.attempts.isEmpty(), "Re-running the same candidate cannot pass, so no retry is offered");
        assertEquals(List.of("validate:validate-error-v1"), store.details("REVISION_REQUIRED"));
        assertFalse(store.has("RETRY_AVAILABLE"));
        assertTrue(
                Files.readString(fixture.evidence(state, "validate-error-v1")).contains("expected 200 but was 500"));
        int events = store.eventCount();
        assertEquals(RunStatus.PAUSED, engine.advance(state.id).status);
        assertEquals(events, store.eventCount(), "Advancing cannot get past a required revision");
        assertEquals(1, validator.requiredTestClasses.size());
    }

    @Test
    void revisingTheUpstreamPatchClearsTheRequiredRevisionAndResetsAttempts() throws Exception {
        validator.answers(() -> {
            throw new IOException("sandbox output was cut short");
        });
        RunState state = engine.advance(patchedRun().id);
        assertEquals(1, state.attempts.get("validate"), "An unclassified failure consumes one attempt");
        assertTrue(store.has("RETRY_AVAILABLE"));
        validator.answers(() -> {
            throw new ValidationFailedException("Candidate does not compile");
        });
        state = engine.advance(state.id);
        assertEquals("validate", state.revisionRequiredTask);
        assertEquals("reviewed change\n", fixture.candidateFile(state, "README.md"));

        state = engine.revise(state.id, "apply", "Fix the compilation error.");
        assertNull(state.revisionRequiredTask);
        assertTrue(state.attempts.isEmpty(), "Revised tasks and everything downstream get a fresh retry budget");
        assertEquals(TaskStatus.PENDING, state.tasks.get("apply"));
        assertEquals(TaskStatus.PENDING, state.tasks.get("validate"));
        assertNull(state.validatedCandidateHash);
        assertEquals("original\n", fixture.candidateFile(state, "README.md"), "Stale patches leave the candidate");
        assertEquals("Fix the compilation error.", state.reviewFeedback.get("apply"));
        assertTrue(
                store.has("ARTIFACTS_STALE") && store.has("PARTIAL_REPLAN") && store.has("REVIEW_FEEDBACK_RECORDED"));

        validator.answers(() -> "Sandboxed Maven tests passed: executed=1");
        state = engine.advance(state.id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertEquals("reviewed change\n", fixture.candidateFile(state, "README.md"));
        assertTrue(Files.exists(fixture.evidence(state, "validate-error-v2")), "Diagnostics are never overwritten");
    }

    @Test
    void infrastructureFailurePausesWithoutConsumingRetriesAndResumesWhenThePlatformReturns() throws Exception {
        validator.answers(() -> {
            throw new InfrastructureException("Docker is not available: start Docker Desktop or Colima");
        });
        String id = patchedRun().id;
        for (int attempt = 0; attempt < 3; attempt++) {
            RunState state = engine.advance(id);
            assertEquals(RunStatus.PAUSED, state.status);
            assertEquals(TaskStatus.PENDING, state.tasks.get("validate"));
            assertTrue(state.attempts.isEmpty(), "Platform failures must not consume the candidate's retries");
            assertNull(state.revisionRequiredTask);
        }
        assertEquals(3, store.details("INFRASTRUCTURE_UNAVAILABLE").size());
        assertFalse(store.has("RETRY_AVAILABLE"));
        assertFalse(Files.exists(root.resolve("evidence").resolve(id).resolve("validate-error-v1.txt")));
        validator.answers(() -> "Sandboxed Maven tests passed: executed=1");
        assertEquals(RunStatus.COMPLETED, engine.advance(id).status);
    }

    @Test
    void anInterruptedValidationStaysRunningAndIsRepeatedByTheNextAdvance() throws Exception {
        validator.answers(() -> {
            throw new InterruptedException("operator shutdown");
        });
        String id = patchedRun().id;
        assertThrows(InterruptedException.class, () -> engine.advance(id));
        assertEquals(TaskStatus.RUNNING, store.load(id).tasks.get("validate"));
        validator.answers(() -> "Sandboxed Maven tests passed: executed=1");
        RunState state = engine.advance(id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertEquals(List.of("interrupted=[validate]; candidateReset=false"), store.details("RUN_RECOVERED"));
    }

    @Test
    void changedTestClassesMustExecuteAndRedValidationTargetsTheRegressionTest() throws Exception {
        fixture.fixture("tests.patch", TEST_PATCH);
        var tests = new TaskSpec(
                "tests",
                Stage.IMPLEMENTATION,
                List.of(),
                TaskKind.PATCH,
                "test_author",
                "Add a regression test",
                "tests.patch",
                List.of(),
                List.of("shortener/src/test"),
                false);
        var red = new TaskSpec(
                "red",
                Stage.VALIDATION,
                List.of("tests"),
                TaskKind.VALIDATE_RED,
                "validator",
                "Confirm the regression fails",
                null,
                List.of(),
                List.of(),
                false);
        RunState state = fixture.start(
                engine,
                RunMode.FIXTURE,
                List.of(
                        tests,
                        red,
                        new TaskSpec(
                                "apply",
                                Stage.VALIDATION,
                                List.of("red"),
                                TaskKind.PATCH,
                                "implementer",
                                "Fix",
                                "change.patch",
                                List.of(),
                                List.of("README.md"),
                                false),
                        validate("validate", List.of("apply"))));
        state = engine.advance(state.id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertEquals(List.of("ExpiryTest"), validator.regressionClasses);
        assertEquals(List.of(Set.of("dev.shortener.link.ExpiryTest")), validator.requiredTestClasses);
        assertEquals(1, store.details("CANDIDATE_VALIDATED").size(), "Only a green suite validates the candidate");
    }

    @Test
    void redValidationNeedsExactlyOneUpstreamTestPatch() throws Exception {
        var red = new TaskSpec(
                "red",
                Stage.VALIDATION,
                List.of(),
                TaskKind.VALIDATE_RED,
                "validator",
                "Confirm the regression fails",
                null,
                List.of(),
                List.of(),
                false);
        RunState state = engine.advance(fixture.start(engine, RunMode.FIXTURE, List.of(red)).id);
        assertEquals(RunStatus.PAUSED, state.status);
        assertEquals(1, state.attempts.get("red"));
        assertTrue(Files.readString(fixture.evidence(state, "red-error-v1"))
                .contains("Red validation requires one test patch dependency"));
    }
}
