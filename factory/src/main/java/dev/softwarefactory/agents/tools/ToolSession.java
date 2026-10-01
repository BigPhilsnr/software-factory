package dev.softwarefactory.agents.tools;

import dev.softwarefactory.governance.Hashes;
import java.util.ArrayList;
import java.util.List;

/** Invocation-scoped limits and audit sink; never records tool arguments or returned source text. */
public final class ToolSession {
    public static final int MAX_MODEL_REQUESTS = 8;
    public static final int MAX_TOOL_CALLS = 12;
    @FunctionalInterface public interface BeforeRequest { void reserve() throws Exception; }
    @FunctionalInterface public interface Audit { void record(String event, String detail) throws Exception; }
    @FunctionalInterface public interface Operation { String run() throws Exception; }

    private final BeforeRequest beforeRequest;
    private final Audit audit;
    private final List<String> outcomes = new ArrayList<>();
    private int requests;
    private int calls;

    public ToolSession(BeforeRequest beforeRequest, Audit audit) {
        this.beforeRequest = beforeRequest;
        this.audit = audit;
    }

    public synchronized void reserveRequest() {
        if (requests >= MAX_MODEL_REQUESTS) throw new SecurityException("Per-answer/task model request limit reached");
        try { beforeRequest.reserve(); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("Could not reserve model request", failure); }
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

    public synchronized void recordFinalization() {
        try { audit.record("TOOL_BUDGET_FINALIZING", "request=" + requests + "/" + MAX_MODEL_REQUESTS + "; tools=" + calls + "/" + MAX_TOOL_CALLS); }
        catch (Exception failure) { throw new IllegalStateException("Could not record tool budget finalization", failure); }
    }

    public synchronized String invoke(String name, String input, Operation operation) throws Exception {
        if (++calls > MAX_TOOL_CALLS) throw new SecurityException("Tool call limit reached");
        long started = System.nanoTime();
        audit.record("TOOL_STARTED", name + ":inputSha256=" + Hashes.sha256(input));
        String result;
        String status;
        try {
            result = operation.run();
            status = "OK";
        } catch (Exception failure) {
            // Provider exceptions may include request bodies or credentials. Return only a safe type.
            result = "Tool unavailable or request denied (" + failure.getClass().getSimpleName()
                + "). Do not claim this operation succeeded.";
            status = "ERROR";
        }
        result = result.substring(0, Math.min(result.length(), 16000));
        outcomes.add(name + " " + status);
        audit.record("TOOL_FINISHED", name + ":" + status + ":outputSha256=" + Hashes.sha256(result)
            + ":elapsedMs=" + (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    public synchronized String summary() { return String.join(", ", outcomes); }
}
