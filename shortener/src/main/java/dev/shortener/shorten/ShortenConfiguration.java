package dev.shortener.shorten;

import dev.shortener.platform.ShortenerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the collaborators of {@link ShortenLink} that need settings: URL rules, codes and the creation quota. */
@Configuration(proxyBeanMethods = false)
public class ShortenConfiguration {
    @Bean
    UrlPolicy urlPolicy(ShortenerProperties properties) {
        return new UrlPolicy(properties.baseUrl());
    }

    @Bean
    CodeGenerator codeGenerator() {
        return new CodeGenerator();
    }

    @Bean
    CreationRateLimiter creationRateLimiter(Clock clock, ShortenerProperties properties, MeterRegistry meters) {
        return new CreationRateLimiter(clock, properties.rateLimit(), meters);
    }
}
