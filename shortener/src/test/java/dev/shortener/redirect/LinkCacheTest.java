package dev.shortener.redirect;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.shortener.link.Link;
import dev.shortener.platform.ShortenerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LinkCacheTest {
    private static final ShortenerProperties.Cache SETTINGS =
            new ShortenerProperties.Cache(100, Duration.ofSeconds(60), 10, Duration.ofSeconds(2));
    private final FakeTicker ticker = new FakeTicker();
    private final LinkCache cache = new LinkCache(SETTINGS, ticker);

    private static Link link(long id, String code) {
        return new Link(id, code, "https://example.com", Instant.EPOCH);
    }

    @Test
    void missesCannotEvictHotLinks() {
        for (int i = 0; i < 50; i++) cache.remember(link(i, "code" + i));
        for (int i = 0; i < 1_000; i++) cache.rememberMissing("miss" + i);
        for (int i = 0; i < 50; i++) assertTrue(cache.find("code" + i).isPresent(), "code" + i);
    }

    @Test
    void creationOverridesAnEarlierMiss() {
        cache.rememberMissing("alias");
        assertTrue(cache.isKnownMissing("alias"));
        cache.remember(link(1, "alias"));
        cache.rememberMissing("alias");
        assertFalse(cache.isKnownMissing("alias"));
        assertTrue(cache.find("alias").isPresent());
    }

    @Test
    void entriesExpireAfterTheirTtl() {
        cache.remember(link(1, "hot1"));
        cache.rememberMissing("gone");
        ticker.advance(Duration.ofSeconds(3));
        assertFalse(cache.isKnownMissing("gone"), "misses are short-lived");
        assertTrue(cache.find("hot1").isPresent());
        ticker.advance(Duration.ofSeconds(60));
        assertTrue(cache.find("hot1").isEmpty());
    }

    @Test
    void publishesCacheMetrics() {
        var registry = new SimpleMeterRegistry();
        cache.bindTo(registry);
        cache.find("absent");
        assertTrue(registry.find("cache.gets")
                        .tag("cache", "shortener.links")
                        .tag("result", "miss")
                        .functionCounter()
                        .count()
                >= 1);
    }

    private static final class FakeTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
