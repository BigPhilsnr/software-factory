package dev.softwarefactory.candidate;

/** Bounded sandbox/compiler feedback safe to return to the drafting agent. */
public final class DraftCheckFailedException extends RuntimeException {
    public DraftCheckFailedException(String message) {
        super(message);
    }
}
