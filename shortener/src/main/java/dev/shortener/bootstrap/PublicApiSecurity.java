package dev.shortener.bootstrap;

import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Spring Security guards only the management surface. Short-link redirects and the public JSON API carry no
 * credentials, so they bypass the filter chain entirely (no logout, login, request cache or session work on
 * the hot redirect path). Health and info are public; every other management path is denied.
 */
@Configuration(proxyBeanMethods = false)
public class PublicApiSecurity {
    @Bean
    SecurityFilterChain managementSecurity(HttpSecurity http, WebEndpointProperties endpoints) throws Exception {
        // Exposed endpoints plus the whole base path, so unexposed endpoints are denied rather than routed to MVC.
        RequestMatcher management = new OrRequestMatcher(EndpointRequest.toAnyEndpoint(),
            PathPatternRequestMatcher.withDefaults().matcher(endpoints.getBasePath() + "/**"));
        return http.securityMatcher(management)
            .csrf(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class)).permitAll()
                .anyRequest().denyAll())
            .build();
    }
}
