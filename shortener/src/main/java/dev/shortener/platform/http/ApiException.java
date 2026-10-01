package dev.shortener.platform.http;

import java.util.OptionalLong;
import org.springframework.http.HttpStatus;

/**
 * A request failure whose HTTP status is decided where it is raised, so {@link ApiErrors} can report any
 * story's failures without knowing them. The message becomes the problem detail and must be safe to show.
 */
public abstract class ApiException extends RuntimeException {
    protected ApiException(String message) {
        super(message);
    }

    protected ApiException(String message, Throwable cause) {
        super(message, cause);
    }

    public abstract HttpStatus status();

    /** Seconds after which repeating the request can succeed; empty when the failure names no delay. */
    public OptionalLong retryAfterSeconds() {
        return OptionalLong.empty();
    }
}
