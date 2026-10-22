package dev.softwarefactory.generation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.sun.net.httpserver.HttpServer;
import dev.softwarefactory.candidate.DraftWorkspace;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.generation.tools.ToolSession;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.platform.FactorySettings;
import dev.softwarefactory.platform.Json;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenLimitRecoveryTest {
    @TempDir
    Path root;

    @Test
    void realAdkLoopStopsAtCheckExhaustionWithoutAnotherProviderRequest() throws Exception {
        Path candidate = Files.createDirectory(root.resolve("candidate"));
        Files.writeString(candidate.resolve("README.md"), "original\n");
        var checkpoint = DraftWorkspace.create(candidate, root.resolve("draft"), "input");
        var exhausted =
                new dev.softwarefactory.platform.GenerationUnavailableException("Check limit: compiler failure");
        var draft =
                new DraftWorkspace(checkpoint, new GitWorkspace(root), List.of("README.md"), saved -> {}, (p, d) -> {
                    throw exhausted;
                });
        String check = "[{\"type\":\"tool_use\",\"id\":\"check\",\"name\":\"check_draft\",\"input\":{}}]";
        try (var provider = new Provider(List.of(response(check, "tool_use")))) {
            var clients = mock(ModelClients.class);
            when(clients.anthropic()).thenReturn(provider.client);
            var runtime = new AdkClaudeRuntime(
                    clients,
                    FactorySettings.from(Map.of("CLAUDE_MODEL", "claude-sonnet-5")),
                    draft.path(),
                    () -> {},
                    (event, detail) -> {});
            assertSame(
                    exhausted,
                    assertThrows(exhausted.getClass(), () -> runtime.draft("implementer", "Check draft", draft)));
            assertEquals(1, provider.requests.size(), "A tool stop must not trigger another paid request");
            assertEquals("original\n", Files.readString(candidate.resolve("README.md")));
        }
    }

    @Test
    void completedDraftToolBatchReturnsToValidationWithoutAnotherProviderCall() {
        var client = mock(AnthropicClient.class);
        try (var session = new ToolSession(
                () -> {
                    throw new AssertionError("No extra paid call");
                },
                (event, detail) -> {},
                null,
                java.time.Clock.systemUTC(),
                true)) {
            for (int i = 0; i < 64; i++) session.invoke("read_draft_file", "README.md", () -> "data");
            var model = new ThinkingAwareClaude("test-model", client, session);
            var request = LlmRequest.builder().model("test-model").build();
            assertThrows(
                    dev.softwarefactory.platform.GenerationUnavailableException.class,
                    () -> model.generateContent(request, false).blockingFirst());
            verifyNoInteractions(client);
        }
    }

    @Test
    void recoveryDoesNotDisableThinkingForOtherModels() throws Exception {
        for (String model : List.of("claude-sonnet-5.5", "unknown-model")) {
            try (var provider = new Provider(List.of(
                            response("[]", "max_tokens"),
                            response("[{\"type\":\"text\",\"text\":\"done\"}]", "end_turn")));
                    var session = new ToolSession(() -> {}, (event, detail) -> {})) {
                var request = LlmRequest.builder()
                        .model(model)
                        .contents(List.of(Content.fromParts(Part.fromText("Request"))))
                        .build();
                new ThinkingAwareClaude(model, provider.client, session)
                        .generateContent(request, false)
                        .blockingFirst();
                assertFalse(provider.requests.get(1).has("thinking"));
                assertEquals(
                        !model.equals("unknown-model"), provider.requests.get(1).has("output_config"));
            }
        }
    }

    @Test
    void retriesSonnetFiveWithThinkingDisabledAndOriginalMessages() throws Exception {
        try (var provider = new Provider(List.of(
                        response(
                                "[{\"type\":\"thinking\",\"thinking\":\"PRIVATE\",\"signature\":\"sig\"}]",
                                "max_tokens"),
                        response("[{\"type\":\"text\",\"text\":\"complete artifact\"}]", "end_turn")));
                var session = new ToolSession(() -> {}, (event, detail) -> {})) {
            var request = LlmRequest.builder()
                    .model("claude-sonnet-5")
                    .contents(List.of(Content.fromParts(Part.fromText("Original request"))))
                    .build();
            var answer = new ThinkingAwareClaude("claude-sonnet-5", provider.client, session)
                    .generateContent(request, false)
                    .blockingFirst();
            assertEquals("complete artifact", answer.content().orElseThrow().text());
            assertEquals(
                    "medium",
                    provider.requests.getFirst().at("/output_config/effort").asText());
            assertEquals(
                    "low", provider.requests.get(1).at("/output_config/effort").asText());
            assertEquals(
                    "disabled", provider.requests.get(1).at("/thinking/type").asText());
            assertEquals(
                    provider.requests.getFirst().get("messages"),
                    provider.requests.get(1).get("messages"));
            assertFalse(provider.requests.get(1).toString().contains("PRIVATE"));
        }
    }

    @Test
    void realAdkLoopEditsScratchAndNeverExecutesTruncatedToolCall() throws Exception {
        Path candidate = Files.createDirectory(root.resolve("candidate"));
        Files.writeString(candidate.resolve("README.md"), "original\n");
        var checkpoint = DraftWorkspace.create(candidate, root.resolve("draft"), "input");
        var draft = new DraftWorkspace(
                checkpoint, new GitWorkspace(root), List.of("README.md"), saved -> {}, (p, d) -> "passed");
        String validTool = tool("README.md", Hashes.sha256("original\n"));
        try (var provider = new Provider(List.of(
                response(tool("../escape", "ABSENT"), "max_tokens"),
                response(validTool, "tool_use"),
                response("[{\"type\":\"text\",\"text\":\"Draft ready\"}]", "end_turn")))) {
            var clients = mock(ModelClients.class);
            when(clients.anthropic()).thenReturn(provider.client);
            var runtime = new AdkClaudeRuntime(
                    clients,
                    FactorySettings.from(Map.of("CLAUDE_MODEL", "claude-sonnet-5")),
                    draft.path(),
                    () -> {},
                    (event, detail) -> {});
            runtime.draft("implementer", "Update README using the draft tool", draft);
            assertTrue(draft.diff().contains("+updated"));
            assertEquals("original\n", Files.readString(candidate.resolve("README.md")));
            assertFalse(Files.exists(root.resolve("escape")));
            assertEquals(3, provider.requests.size());
            assertTrue(provider.requests.getFirst().get("tools").toString().contains("write_draft_file"));
            assertTrue(provider.requests.get(2).get("messages").toString().contains("Saved README.md"));
        }
    }

    private static String tool(String path, String hash) throws Exception {
        return Json.MAPPER.writeValueAsString(List.of(Map.of(
                "type",
                "tool_use",
                "id",
                "tool_write",
                "name",
                "write_draft_file",
                "input",
                Map.of("path", path, "expected_hash", hash, "content", "updated\n"))));
    }

    private static String response(String content, String reason) {
        return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-5\",\"content\":"
                + content + ",\"stop_reason\":\"" + reason + "\",\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}";
    }

    private static final class Provider implements AutoCloseable {
        final HttpServer server;
        final AnthropicClient client;
        final List<JsonNode> requests = new ArrayList<>();

        Provider(List<String> responses) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/messages", exchange -> {
                requests.add(Json.MAPPER.readTree(exchange.getRequestBody().readAllBytes()));
                byte[] bytes = responses
                        .get(Math.min(requests.size() - 1, responses.size() - 1))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            client = AnthropicOkHttpClient.builder()
                    .apiKey("test-only")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .maxRetries(0)
                    .build();
        }

        @Override
        public void close() {
            client.close();
            server.stop(0);
        }
    }
}
