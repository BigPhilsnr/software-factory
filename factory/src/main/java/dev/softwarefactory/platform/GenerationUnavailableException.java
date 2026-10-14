package dev.softwarefactory.platform;

/** Generation could not finish within its resources; preserve the candidate and allow operator recovery. */
public final class GenerationUnavailableException extends RuntimeException {
    public GenerationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public GenerationUnavailableException(String message) {
        super(message);
    }
}
