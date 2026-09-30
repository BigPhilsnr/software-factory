package com.example.shortener.domain;

import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;

public final class ShortenerService {
    private final LinkRepository links;
    private final CodeGenerator codes;
    private final UrlPolicy urls;

    public ShortenerService(LinkRepository links, CodeGenerator codes, UrlPolicy urls) {
        this.links = links;
        this.codes = codes;
        this.urls = urls;
    }

    public Link create(String url) {
        String target = urls.validate(url);
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return links.create(codes.next(), target);
            } catch (DuplicateKeyException collision) {
                // A unique constraint is the final authority under concurrent creation.
            }
        }
        throw new CapacityException("Unable to allocate a short code");
    }

    public Optional<Link> find(String code) {
        return links.findByCode(code);
    }

    public long count(Link link) {
        return links.redirectCount(link.id());
    }

    public void recordRedirect(Link link) {
        links.recordRedirect(link.id());
    }

    public static final class CapacityException extends RuntimeException {
        public CapacityException(String message) { super(message); }
    }
}
