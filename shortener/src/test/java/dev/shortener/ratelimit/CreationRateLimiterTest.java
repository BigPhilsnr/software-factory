package dev.shortener.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.shortener.ShortenerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CreationRateLimiterTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private CreationRateLimiter limiter(Clock clock, int limit, int maxClients) {
        return new CreationRateLimiter(clock, new ShortenerProperties.RateLimit(limit, Duration.ofSeconds(60), maxClients), meters);
    }

    @Test
    void rejectsOnlyAfterLimitAndResetsAtNextUtcMinute() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T12:00:59Z"));
        CreationRateLimiter limiter = limiter(clock, 2, 100);
        assertTrue(limiter.admit("127.0.0.1").allowed());
        assertTrue(limiter.admit("127.0.0.1").allowed());
        var denied = limiter.admit("127.0.0.1");
        assertFalse(denied.allowed());
        assertEquals(1, denied.retryAfterSeconds());
        clock.value = Instant.parse("2026-09-30T12:01:00Z");
        assertTrue(limiter.admit("127.0.0.1").allowed());
    }

    @Test
    void groupsIpv6ClientsByTheirSlash64() {
        CreationRateLimiter limiter = limiter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1, 100);
        assertTrue(limiter.admit("2001:db8:1:2::1").allowed());
        assertFalse(limiter.admit("2001:db8:1:2:ffff::9").allowed());
        assertTrue(limiter.admit("2001:db8:1:3::1").allowed());
    }

    @Test
    void fullTableFallsBackToAggregateBucketsInsteadOfRejectingEveryone() {
        CreationRateLimiter limiter = limiter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1, 2);
        assertTrue(limiter.admit("198.51.100.1").allowed());
        assertTrue(limiter.admit("198.51.100.2").allowed());
        assertTrue(limiter.admit("203.0.113.1").allowed(), "a new network still gets its own aggregate quota");
        assertFalse(limiter.admit("203.0.113.2").allowed(), "same /16 shares the aggregate bucket");
        assertTrue(limiter.admit("192.0.2.1").allowed());
        assertEquals(3, meters.get("shortener.ratelimit.overflow").counter().count());
    }

    @Test
    void sweepsExpiredWindowsBeforeAggregating() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T12:00:00Z"));
        CreationRateLimiter limiter = limiter(clock, 1, 1);
        assertTrue(limiter.admit("198.51.100.1").allowed());
        clock.value = Instant.parse("2026-09-30T12:01:00Z");
        assertTrue(limiter.admit("203.0.113.1").allowed());
        assertFalse(limiter.admit("203.0.113.1").allowed(), "tracked exactly after the sweep, not aggregated");
        assertEquals(0, meters.get("shortener.ratelimit.overflow").counter().count());
    }

    @Test
    void admitsExactlyTheLimitUnderConcurrency() throws Exception {
        int limit = 50;
        int threads = 16;
        int attemptsPerThread = 25;
        CreationRateLimiter limiter = limiter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), limit, 100);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                results.add(pool.submit(() -> {
                    start.await();
                    int allowed = 0;
                    for (int i = 0; i < attemptsPerThread; i++) if (limiter.admit("192.0.2.10").allowed()) allowed++;
                    return allowed;
                }));
            }
            start.countDown();
            int admitted = 0;
            for (Future<Integer> result : results) admitted += result.get(10, TimeUnit.SECONDS);
            assertEquals(limit, admitted);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant value;
        MutableClock(Instant value) { this.value = value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
