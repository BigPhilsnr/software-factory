package dev.shortener.links;

import java.util.Optional;

public interface LinkRepository {
    /** Atomically creates the link and its zeroed statistics; a taken code raises DuplicateKeyException. */
    Link create(String code, String targetUrl);

    Optional<Link> findByCode(String code);

    /** Empty only when the link itself does not exist; a missing statistics row reads as zero redirects. */
    Optional<RedirectStats> statistics(long linkId);
}
