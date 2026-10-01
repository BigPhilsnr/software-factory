package dev.shortener.shorten;

import dev.shortener.platform.http.ApiException;
import org.springframework.http.HttpStatus;

/** Another link already owns the requested alias. */
final class AliasTakenException extends ApiException {
    AliasTakenException() {
        super("Alias already exists");
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
