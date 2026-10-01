package dev.shortener.links;

import dev.shortener.redirects.LinkCache;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;

public final class ShortenerService {
    /** Generated-code collisions are astronomically rare; repeated ones signal a broken generator or keyspace. */
    private static final int MAX_GENERATION_ATTEMPTS = 4;

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

    /** Applies every creation rule without side effects, so only valid requests consume quota. */
    public LinkDraft prepare(String url, String alias) {
        String target = urls.validate(url);
        if (alias == null) return new LinkDraft(target, null);
        String canonical = LinkCodes.canonical(alias)
                .orElseThrow(() -> new InvalidLinkException(
                        "Alias must be 4-32 letters, digits or hyphens and not a reserved word"));
        return new LinkDraft(target, canonical);
    }

    public Link create(LinkDraft draft) {
        if (draft.alias() != null) {
            try {
                return remember(links.create(draft.alias(), draft.targetUrl()));
            } catch (DuplicateKeyException conflict) {
                throw new AliasConflictException(conflict);
            }
        }
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String code = codes.next();
            if (LinkCodes.RESERVED.contains(code)) continue;
            try {
                return remember(links.create(code, draft.targetUrl()));
            } catch (DuplicateKeyException collision) {
                // The unique constraint is the final authority under concurrent creation; retry with a new code.
            }
        }
        throw new CapacityException("Unable to allocate a short code");
    }

    public Optional<Link> find(String code) {
        Optional<String> canonical = LinkCodes.canonical(code);
        if (canonical.isEmpty()) return Optional.empty();
        String key = canonical.get();
        Optional<Link> hit = cache.get(key);
        if (hit.isPresent() || cache.isKnownMissing(key)) return hit;
        Optional<Link> found = links.findByCode(key);
        found.ifPresentOrElse(cache::put, () -> cache.putMiss(key));
        return found;
    }

    public RedirectStats statistics(Link link) {
        return links.statistics(link.id()).orElseThrow(LinkNotFoundException::new);
    }

    private Link remember(Link created) {
        cache.put(created);
        return created;
    }

    public static final class CapacityException extends RuntimeException {
        public CapacityException(String message) {
            super(message);
        }
    }

    public static final class AliasConflictException extends RuntimeException {
        public AliasConflictException(Throwable cause) {
            super("Alias already exists", cause);
        }
    }
}
