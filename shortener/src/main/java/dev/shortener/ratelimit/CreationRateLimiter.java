package dev.shortener.ratelimit;

import dev.shortener.ShortenerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Local fixed-window limiter keyed by client network (IPv4 /32, IPv6 /64). Lock-free: every window update
 * is an atomic {@link ConcurrentHashMap#compute}. When the tracked-client bound is reached, expired windows
 * are swept (at most once per window) and new clients then share a coarser IPv4 /16 or IPv6 /48 bucket
 * instead of being rejected outright. A distributed deployment must replace this adapter.
 */
public final class CreationRateLimiter {
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    private static final int IPV4_AGGREGATE_BYTES = 2;
    private static final int IPV6_CLIENT_BYTES = 8;
    private static final int IPV6_AGGREGATE_BYTES = 6;
    private static final int BITS_PER_BYTE = 8;
    /** Last-resort key once even aggregate buckets reach the bound; keeps memory strictly bounded. */
    private static final String OVERFLOW_BUCKET = "overflow";

    private final Clock clock;
    private final int limit;
    private final long windowSeconds;
    private final int maxTrackedClients;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong lastSweptWindow = new AtomicLong(Long.MIN_VALUE);
    private final Counter aggregated;

    public CreationRateLimiter(Clock clock, ShortenerProperties.RateLimit settings, MeterRegistry meters) {
        this.clock = clock;
        this.limit = settings.requestsPerWindow();
        this.windowSeconds = settings.window().toSeconds();
        if (limit < 1 || windowSeconds < 1) throw new IllegalArgumentException("limit and window must be positive");
        this.maxTrackedClients = settings.maxTrackedClients();
        this.aggregated = Counter.builder("shortener.ratelimit.overflow")
            .description("Creation requests counted in a shared aggregate bucket because the client table was full")
            .register(meters);
    }

    public Result admit(String address) {
        long second = clock.instant().getEpochSecond();
        long window = Math.floorDiv(second, windowSeconds);
        long retryAfter = windowSeconds - Math.floorMod(second, windowSeconds);
        Window counted = windows.compute(key(address, window), (key, previous) ->
            previous == null || previous.id != window ? new Window(window, 1) : previous.increment(limit));
        return new Result(counted.count <= limit, retryAfter);
    }

    private String key(String address, long window) {
        Client client = Client.of(address);
        if (fits(client.exact())) return client.exact();
        sweepExpired(window);
        if (fits(client.exact())) return client.exact();
        aggregated.increment();
        // Aggregates may use up to the same number of entries again; beyond that everyone shares one bucket.
        return windows.containsKey(client.aggregate()) || windows.size() < 2 * maxTrackedClients
            ? client.aggregate() : OVERFLOW_BUCKET;
    }

    private boolean fits(String key) {
        return windows.containsKey(key) || windows.size() < maxTrackedClients;
    }

    private void sweepExpired(long window) {
        long previous = lastSweptWindow.get();
        if (previous != window && lastSweptWindow.compareAndSet(previous, window)) {
            windows.values().removeIf(entry -> entry.id < window);
        }
    }

    private record Window(long id, int count) {
        /** Saturates just above the limit so a flood cannot overflow the counter. */
        Window increment(int limit) { return new Window(id, Math.min(limit + 1, count + 1)); }
    }

    /** The exact key and its coarser aggregate for one socket peer address. */
    record Client(String exact, String aggregate) {
        static Client of(String address) {
            Optional<byte[]> numeric = numericAddress(address);
            if (numeric.isEmpty()) return new Client(address, address);
            byte[] bytes = numeric.get();
            if (bytes.length == IPV6_CLIENT_BYTES * 2) {
                return new Client(prefix(bytes, IPV6_CLIENT_BYTES), prefix(bytes, IPV6_AGGREGATE_BYTES));
            }
            return new Client(address, prefix(bytes, IPV4_AGGREGATE_BYTES));
        }

        private static String prefix(byte[] bytes, int length) {
            return HexFormat.of().formatHex(Arrays.copyOf(bytes, length)) + "/" + length * BITS_PER_BYTE;
        }

        /** Parses only numeric literals (socket peers always are), so no DNS lookup can happen. */
        private static Optional<byte[]> numericAddress(String address) {
            if (!address.contains(":") && !IPV4_LITERAL.matcher(address).matches()) return Optional.empty();
            try {
                return Optional.of(InetAddress.getByName(address).getAddress());
            } catch (UnknownHostException malformed) {
                // Not a usable literal: the raw string still forms a bounded, per-peer key.
                return Optional.empty();
            }
        }
    }

    public record Result(boolean allowed, long retryAfterSeconds) {}
}
