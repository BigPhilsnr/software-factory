package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.platform.InfrastructureException;
import dev.softwarefactory.platform.LogText;
import dev.softwarefactory.scenario.TaskSpec;
import dev.softwarefactory.validation.ValidationFailedException;
import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides what a task failure means for the run:
 * <ul>
 * <li>a policy violation safe-stops the run - nothing may be retried;</li>
 * <li>a platform failure pauses it without consuming the task's retry budget;</li>
 * <li>a reproducible validation failure requires an upstream revision;</li>
 * <li>anything else may be retried once, then fails the run.</li>
 * </ul>
 */
final class FailureHandling {
    /** A task may fail once and be retried; the second failure is terminal. */
    static final int MAX_ATTEMPTS = 2;

    private static final int MAX_DIAGNOSTIC = 4_000;
    private static final Logger LOG = LoggerFactory.getLogger(FailureHandling.class);

    private final RunStore runs;
    private final RunEvidence evidence;
    private final Clock clock;

    FailureHandling(RunStore runs, RunEvidence evidence, Clock clock) {
        this.runs = runs;
        this.evidence = evidence;
        this.clock = clock;
    }

    @FunctionalInterface
    interface Step {
        void run() throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface Work<T> {
        T run() throws IOException, InterruptedException;
    }

    /**
     * Runs one step of a task. A failure is recorded as the task's outcome instead of escaping the scheduler.
     * An interrupt (shutdown) propagates: the task stays RUNNING and is recovered by the next advance.
     *
     * @return whether the step completed
     */
    boolean attempt(RunState state, TaskSpec task, Step step) throws IOException, InterruptedException {
        return produce(state, task, () -> {
                    step.run();
                    return Boolean.TRUE;
                })
                .isPresent();
    }

    /**
     * Like {@link #attempt}, for a step that yields a value.
     *
     * @return the value, or empty when the failure was recorded
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // Boundary: every task failure becomes a recorded outcome.
    <T> Optional<T> produce(RunState state, TaskSpec task, Work<T> work) throws IOException, InterruptedException {
        try {
            return Optional.of(work.run());
        } catch (IOException | RuntimeException failure) {
            fail(state, task, failure);
            return Optional.empty();
        }
    }

    void fail(RunState state, TaskSpec task, Exception failure) throws IOException {
        Throwable cause = rootCause(failure);
        if (cause instanceof SecurityException || state.status == RunStatus.SAFE_STOPPED) {
            safeStop(state, task, String.valueOf(cause.getMessage()));
            return;
        }
        String message = String.valueOf(cause.getMessage());
        if (message.length() > MAX_DIAGNOSTIC) message = message.substring(0, MAX_DIAGNOSTIC);
        if (cause instanceof InfrastructureException) {
            pauseForPlatform(state, task, message, cause);
            return;
        }
        String diagnostic = evidence.recordDiagnostic(state, task.id(), cause, message);
        if (cause instanceof ValidationFailedException && task.kind().isValidation()) {
            requireRevision(state, task, diagnostic);
        } else {
            retryOrFail(state, task, diagnostic, cause);
        }
    }

    void safeStop(RunState state, TaskSpec task, String reason) throws IOException {
        state.tasks.put(task.id(), TaskStatus.FAILED);
        state.status = RunStatus.SAFE_STOPPED;
        state.finishedAt = clock.instant();
        runs.record(state, EventTypes.POLICY_SAFE_STOP, task.id() + ":" + reason);
        LOG.warn(
                "Run {} safe-stopped at task {}: {}",
                LogText.singleLine(state.id),
                LogText.singleLine(task.id()),
                LogText.singleLine(reason));
    }

    /** The platform failed, not the candidate: pause without consuming the task's retry budget. */
    private void pauseForPlatform(RunState state, TaskSpec task, String message, Throwable cause) throws IOException {
        state.tasks.put(task.id(), TaskStatus.PENDING);
        state.status = RunStatus.PAUSED;
        runs.record(state, EventTypes.INFRASTRUCTURE_UNAVAILABLE, task.id() + ":" + message);
        LOG.warn(
                "Run {} paused at task {}: infrastructure unavailable",
                LogText.singleLine(state.id),
                LogText.singleLine(task.id()),
                cause);
    }

    /** Re-running the same candidate cannot pass; an upstream task must be revised. */
    private void requireRevision(RunState state, TaskSpec task, String diagnostic) throws IOException {
        state.tasks.put(task.id(), TaskStatus.FAILED);
        state.revisionRequiredTask = task.id();
        state.status = RunStatus.PAUSED;
        runs.record(state, EventTypes.REVISION_REQUIRED, task.id() + ":" + diagnostic);
        LOG.warn(
                "Run {} requires revision: validation {} failed deterministically",
                LogText.singleLine(state.id),
                LogText.singleLine(task.id()));
    }

    private void retryOrFail(RunState state, TaskSpec task, String diagnostic, Throwable cause) throws IOException {
        int count = state.attempts.merge(task.id(), 1, Integer::sum);
        boolean retry = count < MAX_ATTEMPTS && state.status != RunStatus.FAILED;
        if (retry) {
            state.tasks.put(task.id(), TaskStatus.PENDING);
            state.status = RunStatus.PAUSED;
        } else {
            state.tasks.put(task.id(), TaskStatus.FAILED);
            state.status = RunStatus.FAILED;
            state.finishedAt = clock.instant();
        }
        runs.record(state, retry ? EventTypes.RETRY_AVAILABLE : EventTypes.TASK_FAILED, task.id() + ":" + diagnostic);
        LOG.warn(
                "Run {} task {} failed (attempt {}/{})",
                LogText.singleLine(state.id),
                LogText.singleLine(task.id()),
                count,
                MAX_ATTEMPTS,
                cause);
    }

    /** Unwraps the executor wrappers around a failure raised on a parallel branch. */
    private static Throwable rootCause(Exception failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
