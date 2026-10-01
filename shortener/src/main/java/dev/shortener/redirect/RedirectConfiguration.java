package dev.shortener.redirect;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.shortener.link.LinkRepository;
import dev.shortener.platform.ShortenerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Makes the cached view the {@link LinkRepository} every story reads and writes through. */
@Configuration(proxyBeanMethods = false)
class RedirectConfiguration {
    @Bean
    LinkCache linkCache(ShortenerProperties properties, MeterRegistry meters) {
        var cache = new LinkCache(properties.cache(), Ticker.systemTicker());
        cache.bindTo(meters);
        return cache;
    }

    @Bean
    @Primary
    LinkRepository cachedLinkRepository(@Qualifier("jdbcLinkRepository") LinkRepository stored, LinkCache cache) {
        return new CachedLinkRepository(stored, cache);
    }
}
