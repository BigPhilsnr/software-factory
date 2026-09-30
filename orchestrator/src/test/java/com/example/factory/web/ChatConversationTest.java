package com.example.factory.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ChatConversationTest {
    @TempDir Path root;

    @Test void groundsAnswersRemembersFollowupsAndIsolatesSessions() throws Exception {
        Files.writeString(root.resolve("README.md"), "Repository architecture evidence");
        Files.writeString(root.resolve(".env"), "SECRET_SENTINEL");
        var prompts = new ArrayList<String>();
        var chat = new ChatConversation(root, (role, prompt) -> { prompts.add(prompt); return "Architecture answer"; });
        chat.answer("one", "Explain architecture", "No run selected");
        chat.answer("one", "Why that database?", "No run selected");
        chat.answer("two", "Hello", "No run selected");
        assertTrue(prompts.get(0).contains("Repository architecture evidence"));
        assertFalse(prompts.get(0).contains("SECRET_SENTINEL"));
        assertTrue(prompts.get(1).contains("USER: Explain architecture"));
        assertTrue(prompts.get(1).contains("ASSISTANT: Architecture answer"));
        assertFalse(prompts.get(2).contains("USER: Explain architecture"));
    }

    @Test void failedCallsDoNotPoisonHistoryAndInputIsBounded() throws Exception {
        var prompts = new ArrayList<String>();
        var chat = new ChatConversation(root, (role, prompt) -> {
            prompts.add(prompt);
            if (prompts.size() == 1) throw new IllegalStateException("Provider unavailable");
            return "Recovered";
        });
        assertThrows(IllegalStateException.class, () -> chat.answer("one", "Failed question", ""));
        assertEquals("Recovered", chat.answer("one", "Retry", ""));
        assertFalse(prompts.get(1).contains("Failed question"));
        assertThrows(IllegalArgumentException.class, () -> chat.answer("one", "a".repeat(8001), ""));
        assertEquals(2, prompts.size());
    }
}
