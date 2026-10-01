package dev.shortener.links;

import java.time.Instant;

/** Best-effort recorded GET redirects; null timestamp means no redirect has been recorded. */
public record RedirectStats(long redirectCount, Instant lastRedirectAt) {}
