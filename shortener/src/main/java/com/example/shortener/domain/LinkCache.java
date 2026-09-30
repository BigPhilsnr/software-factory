package com.example.shortener.domain;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded local acceleration for immutable links; PostgreSQL remains authoritative. */
public final class LinkCache {
    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public LinkCache(Clock clock) { this.clock = clock; }

    public Optional<Link> get(String code) {
        Entry entry = entries.get(code);
        if (entry == null) return Optional.empty();
        if (entry.expiresAt <= clock.millis()) {
            entries.remove(code, entry);
            return Optional.empty();
        }
        return Optional.of(entry.link);
    }

    public synchronized void put(Link link) {
        if (entries.size() >= 10_000) entries.clear();
        entries.put(link.code(), new Entry(link, clock.millis() + 60_000));
    }

    private record Entry(Link link, long expiresAt) {}
}
