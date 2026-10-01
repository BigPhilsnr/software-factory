package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.scenario.ScenarioSpec;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.MDC;

/**
 * Generates two independent artifacts concurrently and joins them. Inputs are captured and outcomes are
 * committed under the run-state lock; only the generation itself runs in parallel.
 */
final class ParallelBranches {
    /** How many ready, approval-free artifact tasks are generated side by side. */
    static final int WIDTH = 2;

    private static final Duration JOIN_GRACE = Duration.ofMinutes(1);

    private final RunStore runs;
    private final TaskGeneration generation;
    private final RunEvidence evidence;
    private final FailureHandling failures;
    private final Duration runDeadline;

    ParallelBranches(
            RunStore runs,
            TaskGeneration generation,
            RunEvidence evidence,
            FailureHandling failures,
            Duration runDeadline) {
        this.runs = runs;
        this.generation = generation;
        this.evidence = evidence;
        this.failures = failures;
        this.runDeadline = runDeadline;
    }

    /**
     * @return true when every branch completed and the scheduler may continue
     */
    boolean execute(RunState state, List<TaskSpec> tasks, ScenarioSpec spec) throws IOException, InterruptedException {
        Map<String, Generation> prepared = new HashMap<>();
        synchronized (state) {
            for (TaskSpec task : tasks) {
                state.tasks.put(task.id(), TaskStatus.RUNNING);
                runs.record(state, EventTypes.TASK_STARTED, task.id());
                prepared.put(task.id(), generation.prepare(state, task, spec));
            }
        }
        boolean succeeded;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var completion = new ExecutorCompletionService<String>(executor);
            Map<Future<String>, TaskSpec> pending = new LinkedHashMap<>();
            for (TaskSpec task : tasks) {
                pending.put(completion.submit(() -> generation.generate(state, task, prepared.get(task.id()))), task);
            }
            succeeded = join(state, completion, pending);
            runs.record(
                    state,
                    EventTypes.PARALLEL_JOIN,
                    tasks.get(0).id() + "," + tasks.get(1).id());
        }
        return succeeded;
    }

    private boolean join(RunState state, CompletionService<String> completion, Map<Future<String>, TaskSpec> pending)
            throws IOException, InterruptedException {
        boolean succeeded = true;
        long deadline = System.nanoTime() + runDeadline.plus(JOIN_GRACE).toNanos();
        while (!pending.isEmpty()) {
            Future<String> done = completion.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (done == null) {
                abandon(state, pending);
                return false;
            }
            TaskSpec task = pending.remove(done);
            succeeded &= settle(state, task, done, pending.keySet());
        }
        return succeeded;
    }

    /** Commits one finished branch. A policy stop ends the run, so its siblings are cancelled rather than paid for. */
    private boolean settle(RunState state, TaskSpec task, Future<String> done, Collection<Future<String>> siblings)
            throws IOException, InterruptedException {
        try (var ignoredContext = MDC.putCloseable(RunEngine.TASK_ID, task.id())) {
            String output = done.get();
            synchronized (state) {
                evidence.complete(state, task, output);
            }
            return true;
        } catch (ExecutionException | CancellationException failure) {
            synchronized (state) {
                failures.fail(state, task, failure);
            }
            if (state.status == RunStatus.SAFE_STOPPED) cancelAll(siblings);
            return false;
        }
    }

    private void abandon(RunState state, Map<Future<String>, TaskSpec> pending) throws IOException {
        cancelAll(pending.keySet());
        for (TaskSpec task : pending.values()) {
            synchronized (state) {
                failures.fail(state, task, new TimeoutException("Parallel generation exceeded the run deadline"));
            }
        }
        pending.clear();
    }

    private static void cancelAll(Iterable<Future<String>> futures) {
        for (Future<String> future : futures) future.cancel(true);
    }
}
