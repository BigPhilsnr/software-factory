package dev.shortener.links;

import dev.shortener.analytics.RedirectStats;

import java.util.Optional;

public interface LinkRepository {
    Link create(String code, String targetUrl);
    Optional<Link> findByCode(String code);
    long redirectCount(long linkId);
    RedirectStats statistics(long linkId);
}
