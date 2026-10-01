package dev.shortener.links;

/** A caller supplied invalid link input; other programming errors remain server failures. */
public final class InvalidLinkException extends IllegalArgumentException {
    public InvalidLinkException(String message) {
        super(message);
    }

    public InvalidLinkException(String message, Throwable cause) {
        super(message, cause);
    }
}
