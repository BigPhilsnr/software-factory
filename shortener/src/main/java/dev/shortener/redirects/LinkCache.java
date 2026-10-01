package dev.shortener.redirects;

import dev.shortener.links.Link;

import java.time.Clock;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded local acceleration for immutable links; PostgreSQL remains authoritative. */
public final class LinkCache {
    private final Clock clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    public LinkCache(Clock clock) { this.clock = clock; }

    public synchronized Optional<Link> get(String code) {
        Entry entry = entries.get(code);
        if (entry == null) return Optional.empty();
        if (entry.expiresAt <= clock.millis()) {
            entries.remove(code, entry);
            return Optional.empty();
        }
        return Optional.ofNullable(entry.link);
    }

    public synchronized void put(Link link) {
        entries.put(link.code(), new Entry(link, clock.millis() + 60_000));
        if (entries.size() > 10_000) entries.remove(entries.keySet().iterator().next());
    }

    public synchronized boolean containsMiss(String code) {
        Entry entry = entries.get(code);
        return entry != null && entry.link == null && entry.expiresAt > clock.millis();
    }

    public synchronized void putMiss(String code) {
        entries.putIfAbsent(code, new Entry(null, clock.millis() + 2000));
        if (entries.size() > 10_000) entries.remove(entries.keySet().iterator().next());
    }

    private record Entry(Link link, long expiresAt) {}
}
