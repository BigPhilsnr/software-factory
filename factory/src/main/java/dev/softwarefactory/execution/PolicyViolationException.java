package dev.softwarefactory.execution;

/** A generated change attempted something outside worker authority; the run must safe-stop, not retry. */
public final class PolicyViolationException extends SecurityException {
    public PolicyViolationException(String message) {
        super(message);
    }
}
