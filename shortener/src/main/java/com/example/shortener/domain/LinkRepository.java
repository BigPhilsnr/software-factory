package com.example.shortener.domain;

import java.util.Optional;

public interface LinkRepository {
    Link create(String code, String targetUrl);
    Optional<Link> findByCode(String code);
    long redirectCount(long linkId);
}
