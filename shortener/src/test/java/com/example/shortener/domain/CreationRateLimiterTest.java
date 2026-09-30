package com.example.shortener.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class CreationRateLimiterTest {
    @Test
    void rejectsOnlyAfterLimitAndResetsAtNextUtcMinute() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T12:00:59Z"));
        CreationRateLimiter limiter = new CreationRateLimiter(clock, 2);
        assertTrue(limiter.admit("127.0.0.1").allowed());
        assertTrue(limiter.admit("127.0.0.1").allowed());
        var denied = limiter.admit("127.0.0.1");
        assertFalse(denied.allowed());
        assertEquals(1, denied.retryAfterSeconds());
        clock.value = Instant.parse("2026-09-30T12:01:00Z");
        assertTrue(limiter.admit("127.0.0.1").allowed());
    }

    private static final class MutableClock extends Clock {
        private Instant value;
        MutableClock(Instant value) { this.value = value; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
