package dev.softwarefactory.execution;

/**
 * The candidate itself failed validation reproducibly (compilation, assertions, missing test reports).
 * Re-running the same candidate cannot succeed; an upstream change has to be revised.
 */
public final class ValidationFailedException extends IllegalStateException {
    public ValidationFailedException(String message) {
        super(message);
    }
}
