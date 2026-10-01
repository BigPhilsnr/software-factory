package dev.shortener.link;

import java.time.Instant;

/** An immutable short link: {@code code} is canonical and {@code targetUrl} is in normalized ASCII form. */
public record Link(long id, String code, String targetUrl, Instant createdAt) {}
