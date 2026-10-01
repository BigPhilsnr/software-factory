package dev.shortener.shorten;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The body of {@code POST /api/shorten}; {@code alias} is optional. */
record ShortenLinkRequest(
        @NotBlank @Size(max = UrlPolicy.MAX_URL_BYTES) String url,
        @Pattern(regexp = "[A-Za-z0-9-]{4,32}") String alias) {}
