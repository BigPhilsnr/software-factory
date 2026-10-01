package dev.softwarefactory.operator.web;

import com.google.adk.web.AdkWebServer;
import com.google.adk.web.AgentStaticLoader;
import dev.softwarefactory.agents.ModelClients;
import dev.softwarefactory.configuration.FactorySettings;
import dev.softwarefactory.persistence.AuditKey;
import dev.softwarefactory.persistence.ControlRepository;
import java.io.IOException;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/** Composition root: Spring owns resources; the engine owns approval policy. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({AdkWebServer.class, FactoryWebConfiguration.class})
public class FactoryWebServer {
    private static final Logger LOG = LoggerFactory.getLogger(FactoryWebServer.class);

    public static void main(String[] args) {
        SpringApplication.run(FactoryWebServer.class, args);
    }

    /** The repository checkout that owns scenarios, candidates ({@code .runs/}) and evidence. */
    static Path workspaceRoot(Environment environment) {
        Path root = Path.of(environment.getProperty("factory.workspace", System.getProperty("user.dir"))).toAbsolutePath().normalize();
        return root.getFileName().toString().equals("factory") ? root.getParent() : root;
    }

    @Bean
    FactorySettings factorySettings() {
        FactorySettings settings = FactorySettings.fromEnvironment();
        LOG.info("Factory configuration: {}", settings);
        return settings;
    }

    @Bean
    AuditKey auditKey(FactorySettings settings, Environment environment) throws IOException {
        return AuditKey.resolve(settings.auditKey(), workspaceRoot(environment));
    }

    @Bean(destroyMethod = "close")
    ModelClients modelClients(FactorySettings settings) {
        return new ModelClients(settings);
    }

    @Bean
    @DependsOnDatabaseInitialization
    ControlRepository controlRepository(DataSource dataSource, AuditKey auditKey) {
        return new ControlRepository(dataSource, auditKey);
    }

    @Bean(destroyMethod = "close")
    FactoryService factoryService(ControlRepository repository, FactorySettings settings, ModelClients clients, Environment environment) {
        return new FactoryService(workspaceRoot(environment), repository, settings, clients);
    }

    @Bean
    AgentStaticLoader agentLoader(FactoryService factory) {
        return new AgentStaticLoader(new FactoryAgent(factory));
    }

    /** Reports where to open the UI and whether validation can run, and clears containers left by crashes. */
    @Bean
    ApplicationListener<ApplicationReadyEvent> startupReport(FactoryService factory, Environment environment) {
        return event -> {
            factory.removeOrphanedValidatorContainers(false);
            var validator = factory.validatorStatus();
            if (!validator.ready()) LOG.warn("Sandbox validation unavailable: {}", validator.detail());
            if (!factory.settings().liveReady()) LOG.info("Live generation disabled: {}", factory.settings().liveBlocker());
            String base = "http://localhost:" + environment.getProperty("local.server.port", environment.getProperty("server.port", "8000"));
            LOG.info("Software Factory ready. Operator UI: {}/factory/  ADK chat: {}/dev-ui/?app=software_factory", base, base);
        };
    }
}
