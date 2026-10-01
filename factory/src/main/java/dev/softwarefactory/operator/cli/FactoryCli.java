package dev.softwarefactory.operator.cli;

import dev.softwarefactory.audit.AuditKey;
import dev.softwarefactory.audit.AuditTrail;
import dev.softwarefactory.audit.ControlDatabase;
import dev.softwarefactory.audit.RunJournal;
import dev.softwarefactory.audit.RunLeases;
import dev.softwarefactory.generation.ModelClients;
import dev.softwarefactory.platform.FactorySettings;
import dev.softwarefactory.platform.Json;
import dev.softwarefactory.platform.WorkspaceRoot;
import dev.softwarefactory.run.DurableRunStore;
import dev.softwarefactory.run.RunEngine;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

/** Operator CLI. Runs require a separate control database inaccessible to workers. */
public final class FactoryCli {
    private static final int USAGE_EXIT = 2;

    private FactoryCli() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        // CLI stdout is a machine-readable JSON protocol; diagnostics belong on stderr.
        URL logging = FactoryCli.class.getResource("/factory-logback.xml");
        if (logging != null) System.setProperty("logback.configurationFile", logging.toExternalForm());
        CliCommand command;
        try {
            command = CliCommand.parse(args);
        } catch (UsageException invalid) {
            System.err.println(CliCommand.USAGE);
            System.exit(USAGE_EXIT);
            return;
        }
        System.out.println(render(execute(command)));
    }

    private static Object execute(CliCommand command) throws IOException, InterruptedException {
        FactorySettings settings = FactorySettings.fromEnvironment();
        Path root = WorkspaceRoot.from(Path.of(System.getProperty("user.dir")));
        ControlDatabase database = ControlDatabase.open(
                settings.controlDatabaseUrl(), settings.controlDatabaseUser(), settings.controlDatabasePassword());
        AuditKey key = AuditKey.resolve(settings.auditKey(), root);
        AuditTrail trail = new AuditTrail(database, key);
        DurableRunStore runs = new DurableRunStore(new RunJournal(database, key), trail, new RunLeases(database));
        try (var clients = new ModelClients(settings)) {
            return command.run(new CliActions(new RunEngine(runs, root, settings, clients), runs, trail, root));
        }
    }

    static String render(Object result) throws IOException {
        return result instanceof String text
                ? text
                : Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
    }
}
