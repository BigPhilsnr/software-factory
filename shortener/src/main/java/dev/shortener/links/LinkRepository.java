package dev.shortener.links;


import java.util.Optional;

public interface LinkRepository {
    Link create(String code, String targetUrl);
    Optional<Link> findByCode(String code);
    RedirectStats statistics(long linkId);
}
