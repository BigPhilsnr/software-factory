package com.example.shortener.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class UrlPolicy {
    public String validate(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > 2048 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("URL must be at most 2048 UTF-8 bytes and contain no control characters");
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("URL must be an absolute HTTP or HTTPS URL without credentials");
            }
            return value;
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Malformed URL", ex);
        }
    }
}
