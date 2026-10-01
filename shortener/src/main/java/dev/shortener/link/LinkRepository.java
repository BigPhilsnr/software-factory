package dev.shortener.link;

import java.util.Optional;

/** Durable storage of links, keyed by canonical code. */
public interface LinkRepository {
    /** Atomically creates the link and its zeroed statistics; a taken code raises DuplicateKeyException. */
    Link create(String code, String targetUrl);

    Optional<Link> findByCode(String code);
}
