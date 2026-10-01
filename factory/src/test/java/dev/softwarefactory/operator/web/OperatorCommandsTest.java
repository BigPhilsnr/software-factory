package dev.softwarefactory.operator.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.softwarefactory.agents.ModelClients;
import dev.softwarefactory.configuration.FactorySettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OperatorCommandsTest {
    @TempDir
    Path root;

    private ModelClients clients;
    private FactoryService service;

    @BeforeEach
    void service() {
        var settings = FactorySettings.from(Map.of());
        clients = new ModelClients(settings);
        service = new FactoryService(root, null, settings, clients);
    }

    @AfterEach
    void close() {
        service.close();
        clients.close();
    }

    @Test
    void questionsAndImplementationDiscussionNeverCreateRuns() throws Exception {
        var commands = new OperatorCommands(service, new ChatConversation(root, (role, prompt) -> "Repository answer"));
        assertEquals("Repository answer", commands.handle("session", "Explain the shortener architecture"));
        assertEquals("Repository answer", commands.handle("session", "Add expiration support"));
        assertFalse(Files.exists(root.resolve(".runs")));
    }

    @Test
    void ambiguousApprovalDoesNotDispatchAnActionOrCreateAFeature() throws Exception {
        var commands = new OperatorCommands(service);
        assertTrue(commands.handle("session", "approved").contains("/approve EXACT_HASH"));
        assertTrue(commands.handle("session", "/help").contains("/changes"));
        assertThrows(IllegalArgumentException.class, () -> commands.handle("session", "/approve " + "a".repeat(64)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/feature Add expiry",
                "/demo bugfix",
                "/advance",
                " /approve abc",
                "/reject abc",
                "/reject",
                "/answer yes",
                "/changes apply fix it"
            })
    void workflowCommandsAreClassifiedAsStateChanging(String command) {
        assertTrue(OperatorCommands.changesWorkflowState(command), command);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/help",
                "/status",
                "/review",
                "/runs",
                "/select abc",
                "/tools",
                "How does /advance work?",
                "/advanced"
            })
    void readOnlyCommandsAndQuestionsAreNotStateChanging(String command) {
        assertFalse(OperatorCommands.changesWorkflowState(command), command);
    }
}
