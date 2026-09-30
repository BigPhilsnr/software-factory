package com.example.shortener.config;

import com.example.shortener.domain.CodeGenerator;
import com.example.shortener.domain.LinkRepository;
import com.example.shortener.domain.ShortenerService;
import com.example.shortener.domain.UrlPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainConfiguration {
    @Bean
    ShortenerService shortenerService(LinkRepository links) {
        return new ShortenerService(links, new CodeGenerator(), new UrlPolicy());
    }
}
