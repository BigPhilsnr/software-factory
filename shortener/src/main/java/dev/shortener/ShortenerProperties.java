package dev.shortener;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/** Every tunable of the shortener, bound once and validated at startup. */
@Validated
@ConfigurationProperties("shortener")
public record ShortenerProperties(
        @NotNull URI baseUrl,
        @NotNull @Valid @DefaultValue RateLimit rateLimit,
        @NotNull @Valid @DefaultValue Cache cache,
        @NotNull @Valid @DefaultValue Analytics analytics,
        @NotNull @Valid @DefaultValue Http http) {

    private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");
    private static final Set<String> WEB_SCHEMES = Set.of("http", "https");

    public ShortenerProperties {
        if (baseUrl != null) baseUrl = normalize(baseUrl);
    }

    /** Fails fast on a base URL that cannot prefix short links; strips trailing slashes once. */
    private static URI normalize(URI baseUrl) {
        String scheme = baseUrl.getScheme() == null ? "" : baseUrl.getScheme().toLowerCase(Locale.ROOT);
        if (!WEB_SCHEMES.contains(scheme)
                || baseUrl.getHost() == null
                || baseUrl.getRawQuery() != null
                || baseUrl.getRawFragment() != null
                || baseUrl.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                    "shortener.base-url must be an absolute http(s) URL with a host, got " + baseUrl);
        }
        return URI.create(TRAILING_SLASHES.matcher(baseUrl.toString()).replaceAll(""));
    }

    /** Fixed-window creation quota per client network. */
    public record RateLimit(
            @DefaultValue("30") @Min(1) int requestsPerWindow,
            @DefaultValue("60s") @NotNull Duration window,
            @DefaultValue("10000") @Min(1) int maxTrackedClients) {}

    /** Local acceleration for immutable links; misses are kept apart so they cannot evict hot links. */
    public record Cache(
            @DefaultValue("10000") @Min(1) long maxLinks,
            @DefaultValue("60s") @NotNull Duration linkTtl,
            @DefaultValue("2000") @Min(1) long maxMisses,
            @DefaultValue("2s") @NotNull Duration missTtl) {}

    /** In-memory coalescing of redirect counts and their dedicated connection pool. */
    public record Analytics(
            @DefaultValue("1s") @NotNull Duration flushInterval,
            @DefaultValue("10000") @Min(1) int maxPendingLinks,
            @DefaultValue("500") @Min(1) int flushBatchSize,
            @DefaultValue("30s") @NotNull Duration failureLogInterval,
            @DefaultValue("2") @Min(1) @Max(16) int poolSize,
            @DefaultValue("1s") @NotNull Duration connectionTimeout,
            @DefaultValue("2s") @NotNull Duration queryTimeout) {}

    /** HTTP edge limits. */
    public record Http(
            @DefaultValue("64KB") @NotNull DataSize maxRequestBody,
            @DefaultValue("5s") @NotNull Duration unavailableRetryAfter) {}
}
