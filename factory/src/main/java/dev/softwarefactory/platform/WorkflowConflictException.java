package dev.softwarefactory.platform;

/** The requested transition conflicts with the run's current state; reload and decide again. */
public class WorkflowConflictException extends IllegalStateException {
    public WorkflowConflictException(String message) {
        super(message);
    }
}
