package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.audit.EvidenceStore;
import dev.softwarefactory.scenario.TaskSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The versioned evidence of one run's tasks. Every output, reviewed proposal and failure diagnostic is an
 * immutable file; the run state only records which version is current and its hash.
 */
final class RunEvidence {
    private static final String VERSION = "-v";
    private static final String DIAGNOSTIC = "-error-v";
    private static final String PROPOSAL = "-proposal-v";

    private final EvidenceStore store;
    private final RunStore runs;

    RunEvidence(EvidenceStore store, RunStore runs) {
        this.store = store;
        this.runs = runs;
    }

    /** Stores the output as the task's next evidence version and marks the task done. */
    void complete(RunState state, TaskSpec task, String output) throws IOException {
        String hash = recordOutput(state, task.id(), output);
        runs.record(state, EventTypes.TASK_DONE, task.id() + ":" + hash);
    }

    /**
     * @return the hash of the stored output
     */
    String recordOutput(RunState state, String taskId, String output) throws IOException {
        int version = state.artifactVersions.merge(taskId, 1, Integer::sum);
        String hash = store.write(state.id, taskId + VERSION + version, output);
        state.artifactHashes.put(taskId, hash);
        state.tasks.put(taskId, TaskStatus.DONE);
        return hash;
    }

    /**
     * Stores content under the task's next version without completing the task: what an operator reviews.
     *
     * @return the new version
     */
    int snapshot(RunState state, String taskId, String content) throws IOException {
        int version = state.artifactVersions.merge(taskId, 1, Integer::sum);
        store.write(state.id, taskId + VERSION + version, content);
        return version;
    }

    /** Keeps a generated patch exactly as the agent returned it, before any policy check judges it. */
    void recordProposal(RunState state, String taskId, String proposal) throws IOException {
        int version = state.artifactVersions.getOrDefault(taskId, 0) + 1;
        while (Files.exists(store.path(state.id, taskId + PROPOSAL + version))) version++;
        store.write(state.id, taskId + PROPOSAL + version, proposal);
    }

    /**
     * @return the evidence name of the new diagnostic
     */
    String recordDiagnostic(RunState state, String taskId, Throwable cause, String message) throws IOException {
        int version =
                Math.max(state.diagnosticVersions.getOrDefault(taskId, 0), state.attempts.getOrDefault(taskId, 0)) + 1;
        String diagnostic = taskId + DIAGNOSTIC + version;
        store.write(state.id, diagnostic, cause.getClass().getName() + ": " + message);
        state.diagnosticVersions.put(taskId, version);
        return diagnostic;
    }

    /** The diagnostic of the task's latest failed attempt since its inputs changed, or null. */
    String latestDiagnostic(RunState state, String taskId) throws IOException {
        Integer attempts = state.attempts.get(taskId);
        if (attempts == null) return null;
        // Runs persisted before diagnostic versioning used the attempt count as the version.
        int version = state.diagnosticVersions.getOrDefault(taskId, attempts);
        return Files.readString(store.path(state.id, taskId + DIAGNOSTIC + version));
    }

    /** The file of a specific output version; it may not exist. */
    Path output(RunState state, String taskId, Integer version) {
        return store.path(state.id, taskId + VERSION + version);
    }

    /** The file the run state names as the task's current output. */
    Path currentOutput(RunState state, String taskId) {
        return output(state, taskId, state.artifactVersions.get(taskId));
    }

    String readOutput(RunState state, String taskId, Integer version) throws IOException {
        return Files.readString(output(state, taskId, version));
    }

    String readCurrentOutput(RunState state, String taskId) throws IOException {
        return Files.readString(currentOutput(state, taskId));
    }
}
