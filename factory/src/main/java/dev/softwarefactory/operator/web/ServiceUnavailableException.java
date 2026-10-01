package dev.softwarefactory.operator.web;

/** The factory cannot accept work right now (shutting down, at capacity, platform unavailable); retry later. */
final class ServiceUnavailableException extends RuntimeException {
    ServiceUnavailableException(String message) { super(message); }
}
