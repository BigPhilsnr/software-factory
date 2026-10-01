package dev.shortener.redirects;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.shortener.ShortenerProperties;
import dev.shortener.links.Link;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.util.Optional;

/**
 * Bounded local acceleration for immutable links; PostgreSQL remains authoritative. Misses live in a
 * separate, smaller, short-lived cache so a scan of unknown codes can never evict hot links.
 */
public final class LinkCache {
    private final Cache<String, Link> links;
    private final Cache<String, Boolean> misses;

    public LinkCache(ShortenerProperties.Cache settings, Ticker ticker) {
        this.links = Caffeine.newBuilder()
                .ticker(ticker)
                .maximumSize(settings.maxLinks())
                .expireAfterWrite(settings.linkTtl())
                .recordStats()
                .build();
        this.misses = Caffeine.newBuilder()
                .ticker(ticker)
                .maximumSize(settings.maxMisses())
                .expireAfterWrite(settings.missTtl())
                .recordStats()
                .build();
    }

    public Optional<Link> get(String code) {
        return Optional.ofNullable(links.getIfPresent(code));
    }

    /** A known link always wins over an earlier recorded miss. */
    public void put(Link link) {
        links.put(link.code(), link);
        misses.invalidate(link.code());
    }

    public boolean isKnownMissing(String code) {
        return misses.getIfPresent(code) != null;
    }

    public void putMiss(String code) {
        if (links.getIfPresent(code) == null) misses.put(code, Boolean.TRUE);
    }

    /** Publishes hit, miss, eviction and size meters for both caches. */
    public void bindTo(MeterRegistry registry) {
        CaffeineCacheMetrics.monitor(registry, links, "shortener.links");
        CaffeineCacheMetrics.monitor(registry, misses, "shortener.link-misses");
    }
}
