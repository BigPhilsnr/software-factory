package com.example.shortener.api;

import com.example.shortener.domain.Link;
import com.example.shortener.domain.ShortenerService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.Map;
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
    private final Counter analyticsFailures;

    public ShortenerController(ShortenerService service, @Value("${shortener.base-url}") String baseUrl, MeterRegistry meters) {
        this.service = service;
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.analyticsFailures = meters.counter("shortener.analytics.failures");
    }

    @PostMapping("/api/shorten")
    public ResponseEntity<CreateResponse> create(@RequestBody CreateRequest request) {
        Link link = service.create(request.url());
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreateResponse(link.code(), baseUrl + "/" + link.code()));
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        Link link = service.find(code).orElseThrow(NotFoundException::new);
        try {
            service.recordRedirect(link);
        } catch (RuntimeException failure) {
            analyticsFailures.increment();
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(link.targetUrl())).build();
    }

    @GetMapping("/api/urls/{code}/analytics")
    public Map<String, Object> analytics(@PathVariable String code) {
        Link link = service.find(code).orElseThrow(NotFoundException::new);
        return Map.of("code", code, "redirectCount", service.count(link));
    }

    public record CreateRequest(String url) {}
    public record CreateResponse(String code, String shortUrl) {}
    static final class NotFoundException extends RuntimeException {}
}
