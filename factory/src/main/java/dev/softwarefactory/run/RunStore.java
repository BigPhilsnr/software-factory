package dev.softwarefactory.run;

import dev.softwarefactory.audit.AuditEvent;
import java.io.IOException;
import java.util.Optional;

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

    /** Last committed event, used to distinguish legacy budget stops from policy violations. */
    default Optional<AuditEvent> latestEvent(String id) throws IOException {
        return Optional.empty();
    }

    boolean auditValid(String id) throws IOException;
}
