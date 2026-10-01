package dev.shortener.redirect;

import dev.shortener.link.Link;
import dev.shortener.link.LinkCodes;
import dev.shortener.link.LinkRepository;
import org.springframework.stereotype.Service;

/**
 * The lookup use case: turns whatever spelling a visitor typed into the link it names. Codes that can never
 * name a link (malformed or reserved) are answered without touching storage.
 */
@Service
public final class ResolveLink {
    private final LinkRepository links;
    private final VisitRecorder visits;

    ResolveLink(LinkRepository links, VisitRecorder visits) {
        this.links = links;
        this.visits = visits;
    }

    /** Finds the link without counting a visit, for previews and analytics. */
    public Link resolve(String code) {
        return LinkCodes.canonical(code).flatMap(links::findByCode).orElseThrow(LinkNotFoundException::new);
    }

    /** Finds the link and records that a visitor followed it. */
    Link follow(String code) {
        Link link = resolve(code);
        visits.record(link.id());
        return link;
    }
}
