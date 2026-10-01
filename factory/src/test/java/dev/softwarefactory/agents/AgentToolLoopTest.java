package dev.softwarefactory.agents;

import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.sun.net.httpserver.HttpServer;
import dev.softwarefactory.agents.tools.AnthropicWebSearch;
import dev.softwarefactory.agents.tools.EngineeringTools;
import dev.softwarefactory.agents.tools.PublicWebReader;
import dev.softwarefactory.agents.tools.RepositoryReader;
import dev.softwarefactory.agents.tools.ToolSession;
import dev.softwarefactory.serialization.Json;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentToolLoopTest {
    @TempDir
    Path root;

    @Test
    void repeatedReadsLeaveOneRequestToProduceTheArtifact() throws Exception {
        Files.writeString(root.resolve("README.md"), "Repository evidence marker\n");
        List<com.fasterxml.jackson.databind.JsonNode> requests = new ArrayList<>();
        List<String> audit = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            var request = Json.MAPPER.readTree(exchange.getRequestBody());
            requests.add(request);
            boolean finalizing =
                    request.path("tool_choice").path("type").asText().equals("none");
            int number = requests.size();
            String blocks = finalizing
                    ? "[{\"type\":\"text\",\"text\":\"Final engineering artifact\"}]"
                    : "[{\"type\":\"tool_use\",\"id\":\"read_" + number
                            + "\",\"name\":\"read_file\",\"input\":{\"path\":\"README.md\",\"start_line\":1,\"line_count\":10}}]";
            String response = "{\"id\":\"msg_" + number
                    + "\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"test-model\",\"content\":"
                    + blocks + ",\"stop_reason\":\"" + (finalizing ? "end_turn" : "tool_use")
                    + "\",\"usage\":{\"input_tokens\":10,\"output_tokens\":10}}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var client = AnthropicOkHttpClient.builder()
                .apiKey("unit-test-key")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .maxRetries(0)
                .build();
        AtomicInteger reservations = new AtomicInteger();
        var session = new ToolSession(reservations::incrementAndGet, (event, detail) -> audit.add(event));
        try (var web = new PublicWebReader()) {
            var tools = new EngineeringTools(new RepositoryReader(root), web, query -> "unused", session);
            var agent = LlmAgent.builder()
                    .name("test_agent")
                    .model(new ThinkingAwareClaude("test-model", client, session))
                    .tools(tools.declarations())
                    .build();
            var runner = new InMemoryRunner(agent, "test");
            try {
                runner.sessionService()
                        .createSession("test", "user", Map.of(), "session")
                        .blockingGet();
                StringBuilder answer = new StringBuilder();
                for (var event : runner.runAsync(
                                "user", "session", Content.fromParts(Part.fromText("Inspect then implement")))
                        .blockingIterable()) {
                    if (event.finalResponse()) event.content().ifPresent(content -> answer.append(content.text()));
                }
                assertEquals("Final engineering artifact", answer.toString());
                assertEquals(ToolSession.MAX_MODEL_REQUESTS, reservations.get());
                assertEquals(8, requests.size());
                assertTrue(requests.subList(0, 7).stream()
                        .allMatch(request -> request.path("tool_choice")
                                .path("type")
                                .asText()
                                .equals("auto")));
                assertEquals(
                        "none",
                        requests.getLast().path("tool_choice").path("type").asText());
                assertTrue(requests.getLast().path("system").toString().contains("reserved for your final response"));
                assertEquals(7, audit.stream().filter("TOOL_FINISHED"::equals).count());
                assertTrue(audit.contains("TOOL_BUDGET_FINALIZING"));
                assertThrows(SecurityException.class, session::reserveRequest);
            } finally {
                runner.close().blockingAwait();
            }
        } finally {
            client.close();
            server.stop(0);
        }
    }

    @Test
    void adkExecutesRealReadToolAndPreservesSignedThinkingOnlyInProviderRoundTrip() throws Exception {
        Files.writeString(root.resolve("README.md"), "Repository evidence marker\n");
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String blocks =
                    requests.size() == 1 ? """
                [{"type":"thinking","thinking":"private reasoning marker","signature":"signed-thinking"},
                 {"type":"tool_use","id":"tool_read","name":"read_file","input":{"path":"README.md","start_line":1,"line_count":10}}]
                """ : "[{\"type\":\"text\",\"text\":\"Verified repository evidence\"}]";
            String response =
                    "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"test-model\",\"content\":"
                            + blocks + ",\"stop_reason\":\"" + (requests.size() == 1 ? "tool_use" : "end_turn")
                            + "\",\"usage\":{\"input_tokens\":10,\"output_tokens\":10}}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var client = AnthropicOkHttpClient.builder()
                .apiKey("unit-test-key")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .maxRetries(0)
                .build();
        AtomicInteger reservations = new AtomicInteger();
        var session = new ToolSession(reservations::incrementAndGet, (event, detail) -> {});
        try (var web = new PublicWebReader()) {
            var tools = new EngineeringTools(new RepositoryReader(root), web, query -> "unused", session);
            var agent = LlmAgent.builder()
                    .name("test_agent")
                    .model(new ThinkingAwareClaude("test-model", client, session))
                    .tools(tools.declarations())
                    .build();
            var runner = new InMemoryRunner(agent, "test");
            try {
                runner.sessionService()
                        .createSession("test", "user", Map.of(), "session")
                        .blockingGet();
                StringBuilder events = new StringBuilder();
                for (var event : runner.runAsync("user", "session", Content.fromParts(Part.fromText("Read README")))
                        .blockingIterable()) {
                    events.append(event.toJson());
                }
                assertTrue(events.toString().contains("Verified repository evidence"));
                assertFalse(events.toString().contains("private reasoning marker"));
                assertEquals(2, reservations.get());
                assertEquals("read_file OK", session.summary());
                var first = Json.MAPPER.readTree(requests.getFirst());
                assertEquals(7, first.path("tools").size());
                assertTrue(requests.get(1).contains("signed-thinking"));
                assertTrue(requests.get(1).contains("Repository evidence marker"));
                assertTrue(requests.get(1).contains("tool_result"));
            } finally {
                runner.close().blockingAwait();
            }
        } finally {
            client.close();
            server.stop(0);
        }
    }

    @Test
    void providerSearchRequiresSearchEvidenceAndCountsItsOwnRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean valid = request.contains("web_search_20250305") && request.contains("max_uses");
            String response = """
                {"id":"msg_search","type":"message","role":"assistant","model":"test-model",
                 "content":[{"type":"text","text":"Search summary"},
                 {"type":"web_search_tool_result","tool_use_id":"search_1","content":[
                   {"type":"web_search_result","url":"https://example.com/docs","title":"Official docs","encrypted_content":"opaque"}]}],
                 "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":10}}
                """;
            var json = Json.MAPPER.readTree(response);
            var content = (com.fasterxml.jackson.databind.node.ArrayNode) json.path("content");
            if (calls.get() == 2) content.remove(1);
            if (calls.get() == 3)
                ((com.fasterxml.jackson.databind.node.ObjectNode) content.get(1))
                        .set(
                                "content",
                                Json.MAPPER.readTree(
                                        "{\"type\":\"web_search_tool_result_error\",\"error_code\":\"too_many_requests\"}"));
            byte[] bytes = Json.MAPPER.writeValueAsBytes(json);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(valid ? 200 : 400, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var client = AnthropicOkHttpClient.builder()
                .apiKey("unit-test-key")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .maxRetries(0)
                .build();
        try {
            var search = new AnthropicWebSearch(
                    client, "test-model", new ToolSession(calls::incrementAndGet, (event, detail) -> {}));
            String result = search.search("official documentation");
            assertEquals(1, calls.get());
            assertTrue(result.contains("Search summary"));
            assertTrue(result.contains("https://example.com/docs"));
            assertFalse(result.contains("opaque"));
            assertThrows(IllegalArgumentException.class, () -> search.search("x".repeat(501)));
            assertEquals(1, calls.get());
            assertThrows(IllegalStateException.class, () -> search.search("missing evidence"));
            assertThrows(IllegalStateException.class, () -> search.search("provider search failure"));
            assertEquals(3, calls.get());
        } finally {
            client.close();
            server.stop(0);
        }
    }
}
