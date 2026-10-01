package dev.shortener.bootstrap;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.shortener.ShortenerProperties;
import dev.shortener.links.CodeGenerator;
import dev.shortener.links.LinkRepository;
import dev.shortener.links.ShortenerService;
import dev.shortener.links.UrlPolicy;
import dev.shortener.ratelimit.CreationRateLimiter;
import dev.shortener.redirects.LinkCache;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShortenerProperties.class)
public class DomainConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }

    @Bean CodeGenerator codeGenerator() { return new CodeGenerator(); }

    @Bean
    LinkCache linkCache(ShortenerProperties properties, MeterRegistry meters) {
        var cache = new LinkCache(properties.cache(), Ticker.systemTicker());
        cache.bindTo(meters);
        return cache;
    }

    @Bean UrlPolicy urlPolicy(ShortenerProperties properties) { return new UrlPolicy(properties.baseUrl()); }

    @Bean
    ShortenerService shortenerService(LinkRepository links, CodeGenerator codes, UrlPolicy policy, LinkCache cache) {
        return new ShortenerService(links, codes, policy, cache);
    }

    @Bean
    CreationRateLimiter creationRateLimiter(Clock clock, ShortenerProperties properties, MeterRegistry meters) {
        return new CreationRateLimiter(clock, properties.rateLimit(), meters);
    }
}
