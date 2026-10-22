package dev.softwarefactory.generation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The exact host-owned sandbox build is available even when the candidate baseline is empty. */
final class ValidationContract {
    static final String INSTRUCTIONS = instructions();

    private ValidationContract() {}

    private static String instructions() {
        try (var input = ValidationContract.class.getResourceAsStream("/validation/shortener-pom.xml")) {
            if (input == null) throw new IllegalStateException("Trusted validation POM missing");
            return """


                    Trusted validation contract (control-plane instructions, applies to design and code):
                    The sandbox uses the following host-owned Maven build, NOT candidate pom.xml files.
                    Use its exact Spring Boot version and available dependencies. Adding a dependency to a
                    generated POM cannot make it available in validation. Preserve the JDBC/PostgreSQL stack;
                    do not introduce JPA/Hibernate or Testcontainers, which are not supported by this build.
                    The sandbox supplies PostgreSQL via SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME,
                    SPRING_DATASOURCE_PASSWORD and SPRING_DATASOURCE_DRIVER_CLASS_NAME (also SHORTENER_TEST_DB_*).
                    Use that database in integration tests. Do not start containers, require a Docker socket,
                    use H2, or fetch dependencies. Keep tests enabled and retain their behavioral assertions.
                    Generated tests must also run without relying on a Maven profile: do not add @Tag("integration").
                    Use Spring Boot 4 compatible test imports: ContextConfiguration is in
                    org.springframework.test.context. For HTTP tests use the available REST Assured,
                    MockMvc or JDK HttpClient; do not assume the older Boot TestRestTemplate package exists.
                    Configure Spring Security explicitly for the intended public API instead of disabling
                    security auto-configuration or weakening tests to bypass authentication failures.
                    Report incompatible requirements instead of silently inventing a different build stack.
                    Trusted sandbox POM:
                    """ + new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read trusted validation contract", failure);
        }
    }
}
