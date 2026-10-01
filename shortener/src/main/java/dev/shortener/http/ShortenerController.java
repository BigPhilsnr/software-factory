package dev.shortener.http;

import dev.shortener.analytics.AnalyticsRecorder;
import dev.shortener.links.Link;
import dev.shortener.links.ShortenerService;
import dev.shortener.ratelimit.CreationRateLimiter;

import java.net.URI;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
    private final ShortenerService service;
    private final String baseUrl;
    private final AnalyticsRecorder recorder;
    private final CreationRateLimiter limiter;

    public ShortenerController(ShortenerService service, CreationRateLimiter limiter, AnalyticsRecorder recorder,
                               @Value("${shortener.base-url}") String baseUrl) {
        this.service = service;
        this.limiter = limiter;
        this.recorder = recorder;
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    @PostMapping("/api/shorten")
    public ResponseEntity<CreateResponse> create(@RequestBody CreateRequest request, HttpServletRequest http) {
        CreationRateLimiter.Result rate = limiter.admit(http.getRemoteAddr());
        if (!rate.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(rate.retryAfterSeconds())).build();
        }
        if (request == null) throw new IllegalArgumentException("Request body is required");
        Link link = service.create(request.url(), request.alias());
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreateResponse(link.code(), baseUrl + "/" + link.code()));
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        Link link = service.find(code).orElseThrow(NotFoundException::new);
        recorder.record(link.id());
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(link.targetUrl())).build();
    }

    @RequestMapping(value = "/{code}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> preview(@PathVariable String code) {
        Link link = service.find(code).orElseThrow(NotFoundException::new);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(link.targetUrl())).build();
    }

    @GetMapping("/api/urls/{code}/analytics")
    public AnalyticsResponse analytics(@PathVariable String code) {
        Link link = service.find(code).orElseThrow(NotFoundException::new);
        var stats = service.statistics(link);
        return new AnalyticsResponse(link.code(), stats.redirectCount(), stats.lastRedirectAt());
    }

    public record AnalyticsResponse(String code, long redirectCount, java.time.Instant lastRedirectAt) {}
    public record CreateRequest(String url, String alias) {}
    public record CreateResponse(String code, String shortUrl) {}
    static final class NotFoundException extends RuntimeException {}
}
