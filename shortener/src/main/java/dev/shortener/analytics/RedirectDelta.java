package dev.shortener.analytics;

import java.time.Instant;

/** Redirects coalesced for one link since the previous flush. */
record RedirectDelta(long linkId, long count, Instant lastRedirectAt) {}
