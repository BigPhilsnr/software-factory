package dev.shortener.shorten;

import dev.shortener.link.Link;
import dev.shortener.platform.ShortenerProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/shorten}: turns a JSON request from a client network into a shareable short URL. */
@RestController
class ShortenLinkController {
    private final ShortenLink shortenLink;
    private final String baseUrl;

    ShortenLinkController(ShortenLink shortenLink, ShortenerProperties properties) {
        this.shortenLink = shortenLink;
        this.baseUrl = properties.baseUrl().toString();
    }

    @PostMapping(
            path = "/api/shorten",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ShortenLinkResponse> shorten(
            @Valid @RequestBody ShortenLinkRequest request, HttpServletRequest http) {
        Link link = shortenLink.shorten(request.url(), request.alias(), http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ShortenLinkResponse(link.code(), baseUrl + "/" + link.code()));
    }
}
