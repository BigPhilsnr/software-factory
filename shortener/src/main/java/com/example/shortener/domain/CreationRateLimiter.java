package com.example.shortener.domain;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/** Local fixed-window limiter. A distributed deployment must replace this adapter. */
public final class CreationRateLimiter {
    private final Clock clock;
    private final int limit;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public CreationRateLimiter(Clock clock, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        this.clock = clock;
        this.limit = limit;
    }

    public synchronized Result admit(String address) {
        long second = clock.instant().getEpochSecond();
        long epoch = Math.floorDiv(second, 60);
        if (!windows.containsKey(address) && windows.size() >= 10_000) {
            windows.entrySet().removeIf(entry -> entry.getValue().epoch < epoch);
            if (windows.size() >= 10_000) return new Result(false, 60 - Math.floorMod(second, 60));
        }
        Window window = windows.compute(address, (key, previous) -> {
            if (previous == null || previous.epoch != epoch) return new Window(epoch, 1);
            return new Window(epoch, Math.min(limit + 1, previous.count + 1));
        });
        return new Result(window.count <= limit, 60 - Math.floorMod(second, 60));
    }

    private record Window(long epoch, int count) {}
    public record Result(boolean allowed, long retryAfterSeconds) {}
}
