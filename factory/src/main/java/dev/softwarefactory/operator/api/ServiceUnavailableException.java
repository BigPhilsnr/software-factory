package dev.softwarefactory.operator.api;

/** The factory cannot accept work right now (shutting down, at capacity, platform unavailable); retry later. */
public final class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
