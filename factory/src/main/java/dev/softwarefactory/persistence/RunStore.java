package dev.softwarefactory.persistence;

import dev.softwarefactory.workflow.RunState;
import java.io.IOException;

/** Durable workflow authority, independent of a particular database adapter. */
public interface RunStore {
    class MissingRunException extends IllegalArgumentException {
        public MissingRunException(String message) {
            super(message);
        }
    }

    /** Held for one operator transition; closing releases it. */
    @FunctionalInterface
    interface Lease extends AutoCloseable {
        @Override
        void close() throws IOException;
    }

    RunState load(String id) throws IOException;

    /** Exclusive transition lease; throws a {@code WorkflowConflictException} when another holder exists. */
    Lease lease(String id) throws IOException;

    void record(RunState state, String type, String detail) throws IOException;

    boolean auditValid(String id) throws IOException;
}
