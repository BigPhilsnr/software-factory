package dev.shortener.shorten;

import dev.shortener.platform.http.ApiException;
import org.springframework.http.HttpStatus;

/** Every generated code collided; the caller may retry, the operator should look at the keyspace. */
final class CodeSpaceExhaustedException extends ApiException {
    CodeSpaceExhaustedException() {
        super("Unable to allocate a short code");
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.SERVICE_UNAVAILABLE;
    }
}
