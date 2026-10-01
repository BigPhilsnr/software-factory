package dev.shortener.links;

/** A creation request that passed every validation rule; {@code alias} is canonical or null. */
public record LinkDraft(String targetUrl, String alias) {}
