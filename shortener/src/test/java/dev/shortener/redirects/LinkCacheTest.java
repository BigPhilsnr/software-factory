package dev.shortener.redirects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.shortener.ShortenerProperties;
import dev.shortener.links.Link;
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
        for (int i = 0; i < 50; i++) cache.put(link(i, "code" + i));
        for (int i = 0; i < 1_000; i++) cache.putMiss("miss" + i);
        for (int i = 0; i < 50; i++) assertTrue(cache.get("code" + i).isPresent(), "code" + i);
    }

    @Test
    void creationOverridesAnEarlierMiss() {
        cache.putMiss("alias");
        assertTrue(cache.isKnownMissing("alias"));
        cache.put(link(1, "alias"));
        cache.putMiss("alias");
        assertFalse(cache.isKnownMissing("alias"));
        assertTrue(cache.get("alias").isPresent());
    }

    @Test
    void entriesExpireAfterTheirTtl() {
        cache.put(link(1, "hot1"));
        cache.putMiss("gone");
        ticker.advance(Duration.ofSeconds(3));
        assertFalse(cache.isKnownMissing("gone"), "misses are short-lived");
        assertTrue(cache.get("hot1").isPresent());
        ticker.advance(Duration.ofSeconds(60));
        assertTrue(cache.get("hot1").isEmpty());
    }

    @Test
    void publishesCacheMetrics() {
        var registry = new SimpleMeterRegistry();
        cache.bindTo(registry);
        cache.get("absent");
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
