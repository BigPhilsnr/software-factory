package dev.softwarefactory.generation.tools;

import dev.softwarefactory.generation.UntrustedText;
import dev.softwarefactory.governance.Hashes;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Invocation-scoped limits, deadline and audit sink; never records tool arguments or returned source text.
 * After {@link #close()} late provider callbacks can neither reserve budget nor write audit events.
 */
public final class ToolSession implements AutoCloseable {
    public static final int MAX_MODEL_REQUESTS = 8;
    public static final int MAX_TOOL_CALLS = 12;
    /** Upper bound for one provider request, even with a generous invocation deadline. */
    public static final Duration MAX_REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private static final int MAX_TOOL_RESULT = 16_000;
    private static final String FAILED = "ERROR";
    private static final Logger LOG = LoggerFactory.getLogger(ToolSession.class);

    private final BeforeRequest beforeRequest;
    private final Audit audit;
    private final Instant deadline;
    private final Clock clock;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<String> outcomes = new ArrayList<>();
    private int requests;
    private int calls;

    /** Reserves budget for one provider request; throwing refuses the request. */
    @FunctionalInterface
    public interface BeforeRequest {
        void reserve() throws IOException;
    }

    @FunctionalInterface
    public interface Audit {
        void record(String event, String detail) throws IOException;
    }

    @FunctionalInterface
    public interface Operation {
        String run() throws IOException, InterruptedException;
    }

    public ToolSession(BeforeRequest beforeRequest, Audit audit) {
        this(beforeRequest, audit, null, Clock.systemUTC());
    }

    public ToolSession(BeforeRequest beforeRequest, Audit audit, Instant deadline, Clock clock) {
        this.beforeRequest = beforeRequest;
        this.audit = audit;
        this.deadline = deadline;
        this.clock = clock;
    }

    public synchronized void reserveRequest() {
        requireOpen();
        if (requests >= MAX_MODEL_REQUESTS) throw new SecurityException("Per-answer/task model request limit reached");
        try {
            beforeRequest.reserve();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not reserve model request", failure);
        }
        requests++;
    }

    /** A nested search must leave one provider request available to produce the final artifact. */
    public synchronized void reserveSearchRequest() {
        if (requests >= MAX_MODEL_REQUESTS - 1) {
            throw new IllegalStateException("Web search would consume the reserved final response request");
        }
        reserveRequest();
    }

    /** Call after reserving the next main-model request; the last request must return a final answer. */
    public synchronized boolean toolsAllowed() {
        return requests < MAX_MODEL_REQUESTS && calls < MAX_TOOL_CALLS;
    }

    /** Provider timeout for the next request: never beyond the invocation deadline. */
    public Duration requestTimeout() {
        requireOpen();
        if (deadline == null) return MAX_REQUEST_TIMEOUT;
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isNegative() || remaining.isZero())
            throw new IllegalStateException("Agent invocation deadline exceeded");
        return remaining.compareTo(MAX_REQUEST_TIMEOUT) < 0 ? remaining : MAX_REQUEST_TIMEOUT;
    }

    public synchronized void recordFinalization() {
        record(
                "TOOL_BUDGET_FINALIZING",
                "request=" + requests + "/" + MAX_MODEL_REQUESTS + "; tools=" + calls + "/" + MAX_TOOL_CALLS);
    }

    /**
     * Runs one tool call. A failing tool never fails the invocation: the model is told the tool was
     * unavailable, without any detail of the failure.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // Boundary: any tool failure becomes a safe result.
    public synchronized String invoke(String name, String input, Operation operation) {
        requireOpen();
        calls++;
        if (calls > MAX_TOOL_CALLS) throw new SecurityException("Tool call limit reached");
        long started = System.nanoTime();
        record("TOOL_STARTED", name + ":inputSha256=" + Hashes.sha256(input));
        String result;
        String status;
        try {
            result = operation.run();
            status = "OK";
        } catch (IOException | RuntimeException failure) {
            result = unavailable(failure);
            status = FAILED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result = unavailable(interrupted);
            status = FAILED;
        }
        result = result.substring(0, Math.min(result.length(), MAX_TOOL_RESULT));
        outcomes.add(name + " " + status);
        record(
                "TOOL_FINISHED",
                name + ":" + status + ":outputSha256=" + Hashes.sha256(result) + ":elapsedMs="
                        + (System.nanoTime() - started) / 1_000_000);
        return UntrustedText.block("Tool result: " + name, result);
    }

    /** Provider exceptions may include request bodies or credentials, so only the exception type is returned. */
    private static String unavailable(Exception failure) {
        return "Tool unavailable or request denied (" + failure.getClass().getSimpleName()
                + "). Do not claim this operation succeeded.";
    }

    public synchronized String summary() {
        return String.join(", ", outcomes);
    }

    /** Ends the invocation: subsequent (late) callbacks are refused and not audited. */
    @Override
    public void close() {
        closed.set(true);
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("Agent invocation has ended");
    }

    private void record(String event, String detail) {
        if (closed.get()) {
            LOG.debug("Dropped late audit event {} after the invocation ended", event);
            return;
        }
        try {
            audit.record(event, detail);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not record tool audit event " + event, failure);
        }
    }
}
