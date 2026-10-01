package dev.shortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. Read the sibling packages in story order: {@code shorten} (a client asks for a short link),
 * {@code redirect} (a visitor follows it), {@code analytics} (the visit is counted); {@code link} is the
 * shared domain and {@code platform} the cross-cutting plumbing.
 */
// Spring instantiates the application class as a configuration bean, so it cannot be a utility class.
@SuppressWarnings("PMD.UseUtilityClass")
@SpringBootApplication
public class ShortenerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShortenerApplication.class, args);
    }
}
