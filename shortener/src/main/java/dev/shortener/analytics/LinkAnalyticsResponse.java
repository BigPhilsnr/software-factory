package dev.shortener.analytics;

import java.time.Instant;

/** The analytics body; {@code lastRedirectAt} is null until a redirect has been recorded. */
record LinkAnalyticsResponse(String code, long redirectCount, Instant lastRedirectAt) {}
