package dev.softwarefactory.operator.web;

import java.util.UUID;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class FactoryWebConfiguration implements WebMvcConfigurer {
    private static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
    /** Operator page and APIs: only same-origin scripts, styles and requests; never framed. */
    static final String STRICT_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
        + "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
    /** The bundled ADK dev UI uses inline styles and hosted fonts; it still cannot be framed or load plugins. */
    static final String DEV_UI_POLICY = "object-src 'none'; base-uri 'self'; frame-ancestors 'none'";
    private static final RequestMatcher DEV_UI = request -> request.getRequestURI().startsWith("/dev-ui");
    /** Buffer and bound bodies before the security chain inspects chat commands. */
    private static final int BODY_LIMIT_ORDER = SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10;

    @Override public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/factory/").setViewName("forward:/factory/index.html");
        registry.addRedirectViewController("/factory", "/factory/").setKeepQueryParams(true);
    }

    @Bean
    SecurityFilterChain operatorSecurity(HttpSecurity http, LocalOperatorFilter boundary) {
        return http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
            .headers(headers -> headers
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(frame -> frame.deny())
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(DEV_UI, new StaticHeadersWriter(CONTENT_SECURITY_POLICY, DEV_UI_POLICY)))
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(new NegatedRequestMatcher(DEV_UI),
                    new StaticHeadersWriter(CONTENT_SECURITY_POLICY, STRICT_POLICY))))
            .addFilterBefore(boundary, AuthorizationFilter.class)
            .build();
    }

    @Bean
    FilterRegistrationBean<LocalOperatorFilter> operatorBoundaryRegistration(LocalOperatorFilter boundary) {
        var registration = new FilterRegistrationBean<>(boundary);
        registration.setEnabled(false); // Executed exactly once, inside the security chain.
        return registration;
    }

    @Bean
    FilterRegistrationBean<RequestBodyLimit> requestBodyLimit() {
        var registration = new FilterRegistrationBean<>(new RequestBodyLimit());
        registration.setOrder(BODY_LIMIT_ORDER);
        return registration;
    }

    @Bean OperatorToken factoryToken() { return new OperatorToken(UUID.randomUUID().toString()); }

    @Bean FactoryController factoryController(FactoryService factory, OperatorToken factoryToken) { return new FactoryController(factory, factoryToken); }

    @Bean LocalOperatorFilter localOperatorFilter(OperatorToken factoryToken) { return new LocalOperatorFilter(factoryToken); }
}
