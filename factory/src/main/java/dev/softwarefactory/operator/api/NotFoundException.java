package dev.softwarefactory.operator.api;

/** A requested operator resource (scenario specification, artifact) does not exist. */
public final class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }

    public NotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
