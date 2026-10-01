package dev.softwarefactory.persistence;

import dev.softwarefactory.workflow.RunState;

/** Durable workflow authority, independent of a particular database adapter. */
public interface RunStore {
    class MissingRunException extends IllegalArgumentException {
        public MissingRunException(String message) { super(message); }
    }
    RunState load(String id) throws Exception;
    AutoCloseable lease(String id) throws Exception;
    void record(RunState state, String type, String detail) throws Exception;
    boolean auditValid(String id) throws Exception;
}
