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
    @Bean
    ShortenerService shortenerService(LinkRepository links, @org.springframework.beans.factory.annotation.Value("${shortener.base-url}") String baseUrl) {
        return new ShortenerService(links, new CodeGenerator(), new UrlPolicy(baseUrl), new LinkCache(Clock.systemUTC()));
    }

    @Bean
    CreationRateLimiter creationRateLimiter() {
        return new CreationRateLimiter(Clock.systemUTC(), 30);
    }
}
