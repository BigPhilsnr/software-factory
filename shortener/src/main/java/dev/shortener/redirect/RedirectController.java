package dev.shortener.redirect;

import dev.shortener.link.Link;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /{code}} sends the visitor to the target and counts the visit; {@code HEAD} only previews. */
@RestController
class RedirectController {
    private static final String REFERRER_POLICY = "Referrer-Policy";
    /** Where a short link was clicked is not the target's business. */
    private static final String NO_REFERRER = "no-referrer";

    private static final String CODE_PATH = "/{code}";

    private final ResolveLink resolveLink;

    RedirectController(ResolveLink resolveLink) {
        this.resolveLink = resolveLink;
    }

    @GetMapping(CODE_PATH)
    ResponseEntity<Void> follow(@PathVariable String code) {
        return redirectTo(resolveLink.follow(code));
    }

    /** Lets clients inspect a target without it counting as a redirect. */
    @RequestMapping(value = CODE_PATH, method = RequestMethod.HEAD)
    ResponseEntity<Void> preview(@PathVariable String code) {
        return redirectTo(resolveLink.resolve(code));
    }

    /** Redirects are never cached so every GET is observed and counted. */
    private static ResponseEntity<Void> redirectTo(Link link) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(link.targetUrl()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(REFERRER_POLICY, NO_REFERRER)
                .build();
    }
}
