package dev.shortener.links;


import dev.shortener.redirects.LinkCache;

import java.util.Optional;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;

public final class ShortenerService {
    private final LinkRepository links;
    private final CodeGenerator codes;
    private final UrlPolicy urls;
    private final LinkCache cache;

    public ShortenerService(LinkRepository links, CodeGenerator codes, UrlPolicy urls, LinkCache cache) {
        this.links = links;
        this.codes = codes;
        this.urls = urls;
        this.cache = cache;
    }

    public Link create(String url) {
        return create(url, null);
    }

    public Link create(String url, String alias) {
        String target = urls.validate(url);
        if (alias != null) {
            String normalized = alias.toLowerCase(Locale.ROOT);
            if (!normalized.matches("[a-z0-9-]{4,32}") || Set.of("api", "actuator", "health").contains(normalized)) {
                throw new InvalidLinkException("Invalid alias");
            }
            try {
                Link created = links.create(normalized, target);
                cache.put(created);
                return created;
            } catch (DuplicateKeyException conflict) {
                throw new AliasConflictException();
            }
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                Link created = links.create(codes.next(), target);
                cache.put(created);
                return created;
            } catch (DuplicateKeyException collision) {
                // A unique constraint is the final authority under concurrent creation.
            }
        }
        throw new CapacityException("Unable to allocate a short code");
    }

    public Optional<Link> find(String code) {
        if (code == null) return Optional.empty();
        code = code.toLowerCase(Locale.ROOT);
        if (!code.matches("[a-z0-9-]{4,32}")) return Optional.empty();
        Optional<Link> hit = cache.get(code);
        if (hit.isPresent()) return hit;
        if (cache.containsMiss(code)) return Optional.empty();
        Optional<Link> found = links.findByCode(code);
        if (found.isPresent()) cache.put(found.get()); else cache.putMiss(code);
        return found;
    }

    public RedirectStats statistics(Link link) { return links.statistics(link.id()); }

    public static final class CapacityException extends RuntimeException {
        public CapacityException(String message) { super(message); }
    }

    public static final class AliasConflictException extends RuntimeException {}
}
