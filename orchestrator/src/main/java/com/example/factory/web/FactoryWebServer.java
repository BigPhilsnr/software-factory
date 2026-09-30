package com.example.factory.web;

import com.example.factory.infra.ControlRepository;
import com.google.adk.web.AdkWebServer;
import com.google.adk.web.AgentStaticLoader;
import org.springframework.boot.SpringApplication;
import java.nio.file.Path;
import java.util.Map;

public final class FactoryWebServer {
    private FactoryWebServer() {}
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName().toString().equals("orchestrator")) root = root.getParent();
        var repository = new ControlRepository(
            System.getenv().getOrDefault("CONTROL_DB_URL", "jdbc:postgresql://localhost:5434/control"),
            System.getenv().getOrDefault("CONTROL_DB_USER", "control"),
            System.getenv().getOrDefault("CONTROL_DB_PASSWORD", "control"));
        repository.initialize();
        var factory = new FactoryService(root, repository);
        System.setProperty("adk.agents.loader", "static");
        System.setProperty("server.address", "127.0.0.1");
        System.setProperty("server.port", "8000");
        System.setProperty("logging.config", "classpath:factory-logback.xml");
        System.setProperty("debug", "false");
        System.setProperty("logging.level.root", "WARN");
        System.setProperty("logging.level.org.springframework", "WARN");
        System.setProperty("logging.level.com.google.adk", "WARN");
        System.setProperty("adk.web.cors.origins", "http://localhost:8000,http://127.0.0.1:8000");
        SpringApplication app = new SpringApplication(AdkWebServer.class, FactoryWebConfiguration.class);
        app.setDefaultProperties(Map.of("server.address", "127.0.0.1", "server.port", "8000",
            "spring.main.banner-mode", "off", "logging.level.root", "WARN"));
        app.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("factoryService", factory);
            context.getBeanFactory().registerSingleton("agentLoader", new AgentStaticLoader(new FactoryAgent(factory)));
        });
        Runtime.getRuntime().addShutdownHook(new Thread(factory::close));
        app.run(args);
        System.out.println("Software factory: http://localhost:8000/factory/\nADK chat: http://localhost:8000/dev-ui/?app=software_factory");
    }
}
