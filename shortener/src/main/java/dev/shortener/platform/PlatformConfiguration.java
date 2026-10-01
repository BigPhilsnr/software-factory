package dev.shortener.platform;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds {@link ShortenerProperties} and provides the one clock every time-dependent actor shares. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShortenerProperties.class)
public class PlatformConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
