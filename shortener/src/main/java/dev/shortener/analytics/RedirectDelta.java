package dev.shortener.analytics;

import java.time.Instant;

/** Redirects coalesced for one link since the previous flush. */
public record RedirectDelta(long linkId, long count, Instant lastRedirectAt) {}
