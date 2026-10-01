package dev.shortener.links;

import java.time.Instant;

public record LinkAnalyticsResponse(String code, long redirectCount, Instant lastRedirectAt) {}
