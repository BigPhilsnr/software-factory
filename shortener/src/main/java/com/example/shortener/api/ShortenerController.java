package com.example.shortener.api;

import com.example.shortener.domain.Link;
import com.example.shortener.domain.CreationRateLimiter;
import com.example.shortener.domain.AnalyticsRecorder;
import com.example.shortener.domain.ShortenerService;
import java.net.URI;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @GetMapping("/api/urls/{code}/analytics")
    public Map<String, Object> analytics(@PathVariable String code) {
        Link link = service.find(code).orElseThrow(NotFoundException::new);
        return Map.of("code", code, "redirectCount", service.count(link));
    }

    public record CreateRequest(String url, String alias) {}
    public record CreateResponse(String code, String shortUrl) {}
    static final class NotFoundException extends RuntimeException {}
}
