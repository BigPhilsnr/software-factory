package dev.shortener;

import dev.shortener.platform.ShortenerProperties;
import dev.shortener.platform.http.HttpFilterConfiguration;
import dev.shortener.platform.security.PublicApiSecurity;
import dev.shortener.shorten.ShortenConfiguration;
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
 * Everything a MockMvc slice needs besides the controllers and use cases it scans: the real security chain,
 * filters, URL rules and creation limiter. The clock is fixed, so a rate-limit window never resets.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShortenerProperties.class)
@Import({PublicApiSecurity.class, HttpFilterConfiguration.class, ShortenConfiguration.class})
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
    static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Bean
    Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
