package dev.softwarefactory.operator.web;

import dev.softwarefactory.testing.IsolatedFactoryEnvironment;
import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Points a Spring context at a test-owned schema and Git clone. */
final class FactoryHttpTestEnvironment implements AutoCloseable {
    private final IsolatedFactoryEnvironment environment = new IsolatedFactoryEnvironment("http_test");

    void configure(DynamicPropertyRegistry properties) throws Exception {
        environment.withSchema().withWorkspace();
        properties.add("spring.datasource.url", environment::url);
        properties.add("spring.datasource.username", environment::user);
        properties.add("spring.datasource.password", environment::password);
        properties.add("spring.flyway.schemas", environment::schema);
        properties.add("factory.workspace", () -> environment.workspace().toString());
    }

    Path artifact(String run, String name) { return environment.workspace().resolve("evidence").resolve(run).resolve(name); }

    @Override public void close() throws Exception { environment.close(); }
}
