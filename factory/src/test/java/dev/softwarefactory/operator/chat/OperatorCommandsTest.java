package dev.softwarefactory.operator.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.softwarefactory.generation.ModelClients;
import dev.softwarefactory.operator.api.ControlRecords;
import dev.softwarefactory.operator.api.FactoryService;
import dev.softwarefactory.platform.FactorySettings;
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
        service = new FactoryService(root, new ControlRecords(null, null, null), settings, clients);
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

    @Test
    void everyRunCommandIsRoutedToTheSelectedRun() throws Exception {
        var factory = org.mockito.Mockito.mock(FactoryService.class);
        String id = "00000000-0000-0000-0000-000000000abc";
        var state = new dev.softwarefactory.run.RunState(id, "demo", "hash");
        state.mode = "fixture";
        state.pendingApprovalTask = "apply";
        state.pendingClarificationTask = "clarify";
        org.mockito.Mockito.when(factory.state(id)).thenReturn(state);
        org.mockito.Mockito.when(factory.scenario("bugfix", "fixture")).thenReturn(state);
        org.mockito.Mockito.when(factory.feature("Add expiry")).thenReturn(state);
        org.mockito.Mockito.when(factory.detail(id))
                .thenReturn(Map.of("review", Map.of("task", "apply", "hash", "f".repeat(64))));
        var commands = new OperatorCommands(factory, new ChatConversation(root, (role, prompt) -> "answer"));

        assertThrows(IllegalArgumentException.class, () -> commands.handle("s", "/status"), "No run selected yet");
        String selected = commands.handle("s", "/select " + id);
        assertTrue(selected.contains("Review required: **apply**") && selected.contains("Clarification required"));
        assertTrue(commands.handle("s", "/status").startsWith("Run `" + id + "` · **CREATED** · fixture"));
        assertTrue(commands.handle("s", "/advance").startsWith("Run started."));
        String review = commands.handle("s", "/review");
        assertTrue(review.contains("## Review apply") && review.contains("/approve " + "f".repeat(64)));
        assertTrue(commands.handle("s", "/approve " + "f".repeat(64)).startsWith("Exact-hash approval recorded."));
        assertTrue(commands.handle("s", "/reject " + "f".repeat(64)).startsWith("Run `" + id));
        assertEquals("Answer recorded; the run is advancing.", commands.handle("s", "/answer One  region"));
        assertTrue(commands.handle("s", "/changes apply Make it smaller").startsWith("Review feedback recorded"));
        assertTrue(commands.handle("other", "/demo bugfix").contains("Fixture demonstration started"));
        assertTrue(commands.handle("third", "/feature Add expiry").contains("Feature request saved"));
        assertTrue(commands.handle("s", "/tools").contains("search_web"));
        assertEquals("answer", commands.handle("s", "What is pending?"));

        org.mockito.Mockito.verify(factory, org.mockito.Mockito.times(2)).advance(id);
        org.mockito.Mockito.verify(factory).approve(id, "f".repeat(64));
        org.mockito.Mockito.verify(factory).reject(id, "f".repeat(64));
        org.mockito.Mockito.verify(factory).clarify(id, "One  region");
        org.mockito.Mockito.verify(factory).revise(id, "apply", "Make it smaller");

        for (String incomplete : new String[] {"/reject", "/changes apply", "/approve", "/unknown", "/tools now"}) {
            assertThrows(IllegalArgumentException.class, () -> commands.handle("s", incomplete), incomplete);
        }
        org.mockito.Mockito.when(factory.detail(id)).thenReturn(Map.of());
        assertTrue(commands.handle("s", "/review").startsWith("No approval is pending."));
    }
}
