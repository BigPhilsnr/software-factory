package dev.shortener.links;

import dev.shortener.ShortenerProperties;
import dev.shortener.analytics.AnalyticsRecorder;
import dev.shortener.ratelimit.CreationRateLimitExceededException;
import dev.shortener.ratelimit.CreationRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShortenerController {
    private static final String REFERRER_POLICY = "Referrer-Policy";
    /** Where a short link was clicked is not the target's business. */
    private static final String NO_REFERRER = "no-referrer";

    private final ShortenerService service;
    private final CreationRateLimiter limiter;
    private final AnalyticsRecorder recorder;
    private final String baseUrl;

    public ShortenerController(ShortenerService service, CreationRateLimiter limiter, AnalyticsRecorder recorder,
                               ShortenerProperties properties) {
        this.service = service;
        this.limiter = limiter;
        this.recorder = recorder;
        this.baseUrl = properties.baseUrl().toString();
    }

    /** Only requests that pass validation consume quota; see openapi.yaml. */
    @PostMapping(path = "/api/shorten", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreateLinkResponse> create(@Valid @RequestBody CreateLinkRequest request, HttpServletRequest http) {
        LinkDraft draft = service.prepare(request.url(), request.alias());
        CreationRateLimiter.Result rate = limiter.admit(http.getRemoteAddr());
        if (!rate.allowed()) throw new CreationRateLimitExceededException(rate.retryAfterSeconds());
        Link link = service.create(draft);
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreateLinkResponse(link.code(), baseUrl + "/" + link.code()));
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        Link link = service.find(code).orElseThrow(LinkNotFoundException::new);
        recorder.record(link.id());
        return redirectTo(link);
    }

    /** Lets clients inspect a target without it counting as a redirect. */
    @RequestMapping(value = "/{code}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> preview(@PathVariable String code) {
        return redirectTo(service.find(code).orElseThrow(LinkNotFoundException::new));
    }

    @GetMapping(path = "/api/urls/{code}/analytics", produces = MediaType.APPLICATION_JSON_VALUE)
    public LinkAnalyticsResponse analytics(@PathVariable String code) {
        Link link = service.find(code).orElseThrow(LinkNotFoundException::new);
        RedirectStats stats = service.statistics(link);
        return new LinkAnalyticsResponse(link.code(), stats.redirectCount(), stats.lastRedirectAt());
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
