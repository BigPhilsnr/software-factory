package com.example.factory.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
public class FactoryWebConfiguration implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
    @Override public void addViewControllers(org.springframework.web.servlet.config.annotation.ViewControllerRegistry registry) {
        registry.addViewController("/factory/").setViewName("forward:/factory/index.html");
        registry.addRedirectViewController("/factory", "/factory/").setKeepQueryParams(true);
    }
    @Bean String factoryToken() { return UUID.randomUUID().toString(); }
    @Bean FactoryController factoryController(FactoryService factory, String factoryToken) { return new FactoryController(factory, factoryToken); }
    @Bean OncePerRequestFilter localOperatorFilter(String factoryToken) {
        return new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String origin = request.getHeader("Origin");
                if (!Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(request.getServerName()) || !sameOrigin(origin, request)) {
                    response.sendError(403, "Local same-origin requests only");
                    return;
                }
                boolean mutation = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
                if (mutation && request.getRequestURI().startsWith("/factory/api/")
                        && !factoryToken.equals(request.getHeader("X-Factory-Token"))) {
                    response.sendError(403, "Reload the operator page before taking an action");
                    return;
                }
                if (mutation && !request.getRequestURI().startsWith("/factory/api/")
                        && (request.getContentType() == null || !request.getContentType().startsWith("application/json"))) {
                    response.sendError(415, "JSON required");
                    return;
                }
                chain.doFilter(request, response);
            }
        };
    }

    private static boolean sameOrigin(String origin, HttpServletRequest request) {
        if (origin == null) return true;
        try {
            URI uri = URI.create(origin);
            int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
            return request.getScheme().equals(uri.getScheme()) && request.getServerName().equals(uri.getHost()) && request.getServerPort() == port;
        } catch (IllegalArgumentException failure) { return false; }
    }
}
