package dev.shortener.analytics;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/urls/{code}/analytics}: the redirect totals of one link. */
@RestController
class LinkAnalyticsController {
    private final LinkAnalytics analytics;

    LinkAnalyticsController(LinkAnalytics analytics) {
        this.analytics = analytics;
    }

    @GetMapping(path = "/api/urls/{code}/analytics", produces = MediaType.APPLICATION_JSON_VALUE)
    LinkAnalyticsResponse analytics(@PathVariable String code) {
        return analytics.of(code);
    }
}
