package dev.softwarefactory.operator.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.softwarefactory.operator.chat.OperatorCommands;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalOperatorFilterTest {
    private static final String TOKEN = "expected";
    private MockHttpServletResponse response;

    private int status(String method, String path, String origin, String type, String token, String body)
            throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setServerName("localhost");
        request.setServerPort(8000);
        if (origin != null) request.addHeader("Origin", origin);
        if (token != null) request.addHeader(LocalOperatorFilter.TOKEN_HEADER, token);
        if (type != null) request.setContentType(type);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            request.setContent(bytes);
            request.setAttribute(RequestBodyLimit.BODY_ATTRIBUTE, bytes);
        }
        response = new MockHttpServletResponse();
        new LocalOperatorFilter(new OperatorToken(TOKEN), OperatorCommands::changesWorkflowState)
                .doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        return response.getStatus();
    }

    private static String chat(String text) {
        return "{\"appName\":\"software_factory\",\"userId\":\"u\",\"sessionId\":\"s\",\"newMessage\":{\"role\":\"user\",\"parts\":[{\"text\":\""
                + text + "\"}]}}";
    }

    @Test
    void protectsAdkMutationsThroughOriginAndContentTypeAndOperatorMutationsThroughToken() throws Exception {
        assertEquals(403, status("POST", "/run", "https://attacker.example", "application/json", null, chat("hello")));
        assertTrue(response.getContentAsString().contains("\"error\""), "Filter errors carry a JSON body");
        assertEquals(415, status("POST", "/run", null, "application/x-www-form-urlencoded", null, "a=b"));
        assertEquals(200, status("POST", "/run", "http://localhost:8000", "application/json", null, chat("hello")));
        assertEquals(403, status("POST", "/factory/api/runs", null, "application/json", null, "{}"));
        assertEquals(403, status("POST", "/factory/api/runs", null, "application/json", "wrong", "{}"));
        assertEquals(200, status("POST", "/factory/api/runs", null, "application/json", TOKEN, "{}"));
    }

    @Test
    void workflowChangingChatCommandsRequireTheOperatorToken() throws Exception {
        for (String path : new String[] {"/run", "/run_sse"}) {
            assertEquals(
                    403,
                    status("POST", path, null, "application/json", null, chat("/approve " + "a".repeat(64))),
                    path);
            assertEquals(403, status("POST", path, null, "application/json", null, chat("  /advance")), path);
            assertEquals(403, status("POST", path, null, "application/json", null, "not json"), path);
            assertEquals(
                    200,
                    status("POST", path, null, "application/json", TOKEN, chat("/approve " + "a".repeat(64))),
                    path);
            assertEquals(200, status("POST", path, null, "application/json", null, chat("/status")), path);
        }
    }

    @Test
    void liveSessionsRequireTheToken() throws Exception {
        assertEquals(403, status("GET", "/run_live", null, null, null, null));
        assertEquals(200, status("GET", "/run_live", null, null, TOKEN, null));
    }

    @Test
    void nonLoopbackHostsAreRefused() throws Exception {
        var request = new MockHttpServletRequest("GET", "/factory/api/config");
        request.setServerName("factory.example");
        response = new MockHttpServletResponse();
        new LocalOperatorFilter(new OperatorToken(TOKEN), OperatorCommands::changesWorkflowState)
                .doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        assertEquals(403, response.getStatus());
    }
}
