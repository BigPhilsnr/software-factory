package dev.shortener.bootstrap;

import dev.shortener.links.CodeGenerator;
import dev.shortener.links.LinkRepository;
import dev.shortener.links.ShortenerService;
import dev.shortener.links.UrlPolicy;
import dev.shortener.ratelimit.CreationRateLimiter;
import dev.shortener.redirects.LinkCache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class DomainConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean CodeGenerator codeGenerator() { return new CodeGenerator(); }
    @Bean LinkCache linkCache(Clock clock) { return new LinkCache(clock); }
    @Bean UrlPolicy urlPolicy(@org.springframework.beans.factory.annotation.Value("${shortener.base-url}") String baseUrl) {
        return new UrlPolicy(baseUrl);
    }

    @Bean
    ShortenerService shortenerService(LinkRepository links, CodeGenerator codes, UrlPolicy policy, LinkCache cache) {
        return new ShortenerService(links, codes, policy, cache);
    }

    @Bean
    CreationRateLimiter creationRateLimiter(Clock clock) {
        return new CreationRateLimiter(clock, 30);
    }
}
