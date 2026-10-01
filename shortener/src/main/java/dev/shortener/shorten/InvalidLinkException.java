package dev.shortener.shorten;

import dev.shortener.platform.http.ApiException;
import org.springframework.http.HttpStatus;

/** A caller supplied an unacceptable target URL or alias; other programming errors remain server failures. */
final class InvalidLinkException extends ApiException {
    InvalidLinkException(String message) {
        super(message);
    }

    InvalidLinkException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }
}
