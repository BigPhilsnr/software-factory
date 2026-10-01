package dev.shortener.platform.http;

import dev.shortener.platform.ShortenerProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Explicit servlet filter ordering. Both filters run before every request-wrapping filter and well before
 * Spring Security (order -100), so even rejected requests carry a request id.
 */
@Configuration(proxyBeanMethods = false)
public class HttpFilterConfiguration {
    private static final int CORRELATION_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    private static final int BODY_LIMIT_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    @Bean
    FilterRegistrationBean<RequestCorrelation> requestCorrelation() {
        var registration = new FilterRegistrationBean<>(new RequestCorrelation());
        registration.setOrder(CORRELATION_ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    FilterRegistrationBean<RequestBodyLimit> requestBodyLimit(ShortenerProperties properties) {
        int maxBytes = Math.toIntExact(properties.http().maxRequestBody().toBytes());
        var registration = new FilterRegistrationBean<>(new RequestBodyLimit(maxBytes));
        registration.setOrder(BODY_LIMIT_ORDER);
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
