package dev.shortener.shorten;

import dev.shortener.platform.http.ApiException;
import java.util.OptionalLong;
import org.springframework.http.HttpStatus;

/** The caller exhausted its creation quota for the current fixed window. */
final class CreationRateLimitExceededException extends ApiException {
    private final long retryAfterSeconds;

    CreationRateLimitExceededException(long retryAfterSeconds) {
        super("Creation rate limit exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.TOO_MANY_REQUESTS;
    }

    @Override
    public OptionalLong retryAfterSeconds() {
        return OptionalLong.of(retryAfterSeconds);
    }
}
