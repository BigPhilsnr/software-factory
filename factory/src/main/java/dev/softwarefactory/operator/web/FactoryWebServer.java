package dev.softwarefactory.operator.web;

import com.google.adk.web.AdkWebServer;
import com.google.adk.web.AgentStaticLoader;
import dev.softwarefactory.persistence.ControlRepository;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/** Composition root: Spring owns resources; the engine owns approval policy. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({AdkWebServer.class, FactoryWebConfiguration.class})
public class FactoryWebServer {
    public static void main(String[] args) {
        SpringApplication.run(FactoryWebServer.class, args);
    }

    @Bean
    @DependsOnDatabaseInitialization
    ControlRepository controlRepository(DataSource dataSource) {
        return new ControlRepository(dataSource);
    }

    @Bean(destroyMethod = "close")
    FactoryService factoryService(ControlRepository repository, Environment environment) {
        Path root = Path.of(environment.getProperty("factory.workspace", System.getProperty("user.dir"))).toAbsolutePath().normalize();
        if (root.getFileName().toString().equals("factory")) root = root.getParent();
        return new FactoryService(root, repository);
    }

    @Bean
    AgentStaticLoader agentLoader(FactoryService factory) {
        return new AgentStaticLoader(new FactoryAgent(factory));
    }
}
