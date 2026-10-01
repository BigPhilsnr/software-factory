package dev.shortener.links;

import dev.shortener.ShortenerProperties;
import dev.shortener.bootstrap.HttpFilterConfiguration;
import dev.shortener.bootstrap.PublicApiSecurity;
import dev.shortener.ratelimit.CreationRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.info.InfoEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.actuate.web.ServletManagementContextAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Real controller, error mapping, security chain, filters and limiter for MockMvc slices. Only the
 * service and analytics recorder are replaced by the tests. The clock is fixed, so a window never resets.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShortenerProperties.class)
@Import({PublicApiSecurity.class, HttpFilterConfiguration.class})
@ImportAutoConfiguration({
    EndpointAutoConfiguration.class,
    WebEndpointAutoConfiguration.class,
    HealthContributorAutoConfiguration.class,
    HealthContributorRegistryAutoConfiguration.class,
    HealthEndpointAutoConfiguration.class,
    InfoEndpointAutoConfiguration.class,
    ManagementContextAutoConfiguration.class,
    ServletManagementContextAutoConfiguration.class
})
class WebSliceConfiguration {
    /** Properties every slice test applies: a small quota and no database health contributor. */
    static final String SMALL_QUOTA = "shortener.rate-limit.requests-per-window=3";

    static final String NO_DB_HEALTH = "management.endpoint.health.validate-group-membership=false";
    static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Bean
    Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    CreationRateLimiter creationRateLimiter(Clock clock, ShortenerProperties properties, MeterRegistry meters) {
        return new CreationRateLimiter(clock, properties.rateLimit(), meters);
    }
}
