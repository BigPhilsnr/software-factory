package dev.shortener.shorten;

/** A creation request that passed every validation rule; {@code alias} is canonical or null. */
record LinkDraft(String targetUrl, String alias) {}
