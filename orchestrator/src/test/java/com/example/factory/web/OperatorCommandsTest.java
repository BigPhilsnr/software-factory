package com.example.factory.web;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OperatorCommandsTest {
    @TempDir Path root;
    @Test void questionsAndImplementationDiscussionNeverCreateRuns() throws Exception {
        try (var service = new FactoryService(root, null)) {
            var chat = new ChatConversation(root, (role, prompt) -> "Repository answer");
            var commands = new OperatorCommands(service, chat);
            assertEquals("Repository answer", commands.handle("session", "Explain the shortener architecture"));
            assertEquals("Repository answer", commands.handle("session", "Add expiration support"));
            assertFalse(java.nio.file.Files.exists(root.resolve(".runs")));
        }
    }
    @Test void ambiguousApprovalDoesNotDispatchAnActionOrCreateAFeature() throws Exception {
        try (var service = new FactoryService(root, null)) {
            var commands = new OperatorCommands(service);
            assertTrue(commands.handle("session", "approved").contains("/approve EXACT_HASH"));
            assertTrue(commands.handle("session", "/help").contains("/changes"));
            assertThrows(IllegalArgumentException.class, () -> commands.handle("session", "/approve " + "a".repeat(64)));
        }
    }
}
