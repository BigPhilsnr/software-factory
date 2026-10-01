package dev.shortener.analytics;

import java.util.Optional;

/** Reads the durable redirect totals of one link. */
@FunctionalInterface
public interface RedirectStatsReader {
    /** Empty only when the link itself does not exist; a missing statistics row reads as zero redirects. */
    Optional<RedirectStats> forLink(long linkId);
}
