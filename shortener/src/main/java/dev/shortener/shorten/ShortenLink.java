package dev.shortener.shorten;

import dev.shortener.link.Link;
import dev.shortener.link.LinkCodes;
import dev.shortener.link.LinkRepository;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The creation use case, in order: validate the target and alias, reserve the client's quota, choose a code
 * and store the link. Validation has no side effects, so only valid requests consume quota (see openapi.yaml).
 */
@Service
final class ShortenLink {
    /** Generated-code collisions are astronomically rare; repeated ones signal a broken generator or keyspace. */
    private static final int MAX_GENERATION_ATTEMPTS = 4;

    private final LinkRepository links;
    private final CodeGenerator codes;
    private final UrlPolicy urls;
    private final CreationRateLimiter quota;

    ShortenLink(LinkRepository links, CodeGenerator codes, UrlPolicy urls, CreationRateLimiter quota) {
        this.links = links;
        this.codes = codes;
        this.urls = urls;
        this.quota = quota;
    }

    Link shorten(String url, String alias, String clientAddress) {
        LinkDraft draft = validate(url, alias);
        reserveQuota(clientAddress);
        return draft.alias() == null ? storeUnderGeneratedCode(draft.targetUrl()) : storeUnderAlias(draft);
    }

    private LinkDraft validate(String url, String alias) {
        String target = urls.validate(url);
        if (alias == null) return new LinkDraft(target, null);
        String canonical = LinkCodes.canonical(alias)
                .orElseThrow(() -> new InvalidLinkException(
                        "Alias must be 4-32 letters, digits or hyphens and not a reserved word"));
        return new LinkDraft(target, canonical);
    }

    private void reserveQuota(String clientAddress) {
        CreationRateLimiter.Result admission = quota.admit(clientAddress);
        if (!admission.allowed()) throw new CreationRateLimitExceededException(admission.retryAfterSeconds());
    }

    private Link storeUnderAlias(LinkDraft draft) {
        return storeUnlessTaken(draft.alias(), draft.targetUrl()).orElseThrow(AliasTakenException::new);
    }

    private Link storeUnderGeneratedCode(String targetUrl) {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String code = codes.next();
            if (LinkCodes.RESERVED.contains(code)) continue;
            Optional<Link> stored = storeUnlessTaken(code, targetUrl);
            if (stored.isPresent()) return stored.get();
        }
        throw new CodeSpaceExhaustedException();
    }

    /** The unique constraint is the final authority under concurrent creation; empty means the code is taken. */
    private Optional<Link> storeUnlessTaken(String code, String targetUrl) {
        try {
            return Optional.of(links.create(code, targetUrl));
        } catch (DuplicateKeyException taken) {
            return Optional.empty();
        }
    }
}
