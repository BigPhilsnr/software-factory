package dev.shortener.analytics;

import dev.shortener.link.Link;
import dev.shortener.redirect.LinkNotFoundException;
import dev.shortener.redirect.ResolveLink;
import org.springframework.stereotype.Service;

/** The reporting use case: how often, and when last, the link behind a code was followed. */
@Service
final class LinkAnalytics {
    private final ResolveLink resolveLink;
    private final RedirectStatsReader stats;

    LinkAnalytics(ResolveLink resolveLink, RedirectStatsReader stats) {
        this.resolveLink = resolveLink;
        this.stats = stats;
    }

    /** Looking at analytics is not a visit, so the link is resolved without recording one. */
    LinkAnalyticsResponse of(String code) {
        Link link = resolveLink.resolve(code);
        RedirectStats totals = stats.forLink(link.id()).orElseThrow(LinkNotFoundException::new);
        return new LinkAnalyticsResponse(link.code(), totals.redirectCount(), totals.lastRedirectAt());
    }
}
