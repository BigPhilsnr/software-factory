package dev.shortener.redirect;

import dev.shortener.platform.http.ApiException;
import org.springframework.http.HttpStatus;

/** No link exists for the requested code. */
public final class LinkNotFoundException extends ApiException {
    public LinkNotFoundException() {
        super("Unknown short code");
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
