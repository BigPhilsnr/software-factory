package dev.shortener.links;

import dev.shortener.analytics.AnalyticsRecorder;
import dev.shortener.ratelimit.CreationRateLimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Seeded brownfield defect: a creation limit must never throttle redirects. */
class RedirectRateLimitRegressionTest {
    @Test
    void redirectsRemainAvailableAfterCreationLimitWouldBeExhausted() {
        Link link = new Link(1L, "abc12345", "https://example.com", Instant.now());
        ShortenerService service = mock(ShortenerService.class);
        when(service.find(link.code())).thenReturn(Optional.of(link));
        AnalyticsRecorder recorder = id -> {};
        ShortenerController controller = new ShortenerController(
            service, new CreationRateLimiter(Clock.systemUTC(), 30), recorder, "http://localhost:8080");

        for (int i = 0; i < 35; i++) {
            assertEquals(HttpStatus.FOUND, controller.redirect(link.code()).getStatusCode(), "redirect " + i);
        }
    }
}
