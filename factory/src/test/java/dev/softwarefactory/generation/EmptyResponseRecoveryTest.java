package dev.softwarefactory.generation;

import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.sun.net.httpserver.HttpServer;
import dev.softwarefactory.generation.tools.ToolSession;
import dev.softwarefactory.platform.GenerationUnavailableException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EmptyResponseRecoveryTest {
    static Stream<Arguments> responses() {
        return Stream.of(
                Arguments.of("[]", "end_turn", 8, 2),
                Arguments.of("[{\"type\":\"text\",\"text\":\"   \"}]", "end_turn", 8, 2),
                Arguments.of(
                        "[{\"type\":\"thinking\",\"thinking\":\"PRIVATE_SENTINEL\",\"signature\":\"signature\"}]",
                        "end_turn",
                        8,
                        2),
                Arguments.of("[]", "max_tokens", 8, 2),
                Arguments.of("[{\"type\":\"text\",\"text\":\"partial patch\"}]", "max_tokens", 8, 2),
                Arguments.of("[]", "max_tokens", 1, 2),
                Arguments.of("[]", "refusal", 8, 1),
                Arguments.of("[]", "end_turn", 1, 2));
    }

    @ParameterizedTest
    @MethodSource("responses")
    void emptyOutputIsBoundedAuditedAndRecoverable(String content, String stopReason, int budget, int expected)
            throws Exception {
        var requests = new AtomicInteger();
        var reservations = new AtomicInteger();
        var audit = new ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            String response = "{\"id\":\"msg_empty\",\"type\":\"message\",\"role\":\"assistant\","
                    + "\"model\":\"test-model\",\"content\":" + content
                    + ",\"stop_reason\":\"" + stopReason + "\",\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("request-id", "req_test");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var client = AnthropicOkHttpClient.builder()
                .apiKey("secret-key")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .maxRetries(0)
                .build();
        try (var session = new ToolSession(
                new ToolSession.BeforeRequest() {
                    @Override
                    public void reserve() {
                        reservations.incrementAndGet();
                    }
                },
                (event, detail) -> audit.add(event + ":" + detail))) {
            var model = new ThinkingAwareClaude("test-model", client, session);
            var request = LlmRequest.builder()
                    .model("test-model")
                    .contents(List.of(Content.fromParts(Part.fromText("PRIVATE_PROMPT"))))
                    .build();
            assertThrows(
                    GenerationUnavailableException.class,
                    () -> model.generateContent(request, false).blockingFirst());
            assertEquals(expected, requests.get());
            assertEquals(expected, reservations.get());
            String metadata = audit.toString();
            assertTrue(metadata.contains("requestId=req_test"));
            assertTrue(metadata.contains("stopReason=" + stopReason));
            assertTrue(metadata.contains("inputTokens=10"));
            assertTrue(metadata.contains("outputTokens=2"));
            assertEquals(
                    expected == 2 && "end_turn".equals(stopReason), metadata.contains("MODEL_EMPTY_RESPONSE_RETRY"));
            assertEquals(
                    expected == 2 && "max_tokens".equals(stopReason), metadata.contains("MODEL_OUTPUT_LIMIT_RETRY"));
            assertFalse(metadata.contains("PRIVATE_SENTINEL"));
            assertFalse(metadata.contains("PRIVATE_PROMPT"));
            assertFalse(metadata.contains("secret-key"));
        } finally {
            client.close();
            server.stop(0);
        }
    }
}
