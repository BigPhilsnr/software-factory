package dev.shortener.ratelimit;

/** The caller exhausted its creation quota for the current fixed window. */
public final class CreationRateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public CreationRateLimitExceededException(long retryAfterSeconds) {
        super("Creation rate limit exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
