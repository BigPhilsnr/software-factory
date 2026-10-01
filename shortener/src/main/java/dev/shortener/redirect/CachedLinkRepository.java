package dev.shortener.redirect;

import dev.shortener.link.Link;
import dev.shortener.link.LinkRepository;
import java.util.Optional;

/**
 * Puts the {@link LinkCache} in front of durable storage. Links are immutable, so a cached link keeps
 * redirecting while PostgreSQL is briefly unavailable; a new link is remembered at once so it overrides any
 * earlier miss for its code.
 */
final class CachedLinkRepository implements LinkRepository {
    private final LinkRepository stored;
    private final LinkCache cache;

    CachedLinkRepository(LinkRepository stored, LinkCache cache) {
        this.stored = stored;
        this.cache = cache;
    }

    @Override
    public Link create(String code, String targetUrl) {
        Link created = stored.create(code, targetUrl);
        cache.remember(created);
        return created;
    }

    @Override
    public Optional<Link> findByCode(String code) {
        Optional<Link> hit = cache.find(code);
        if (hit.isPresent() || cache.isKnownMissing(code)) return hit;
        Optional<Link> found = stored.findByCode(code);
        found.ifPresentOrElse(cache::remember, () -> cache.rememberMissing(code));
        return found;
    }
}
