package dev.shortener.links;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateLinkRequest(
        @NotBlank @Size(max = UrlPolicy.MAX_URL_BYTES) String url,
        @Pattern(regexp = "[A-Za-z0-9-]{4,32}") String alias) {}
