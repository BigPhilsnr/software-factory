package dev.shortener.links;

/** No link exists for the requested code. */
public final class LinkNotFoundException extends RuntimeException {
    public LinkNotFoundException() { super("Unknown short code"); }
}
