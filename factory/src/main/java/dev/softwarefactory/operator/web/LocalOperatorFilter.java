package dev.softwarefactory.operator.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.Content;
import dev.softwarefactory.serialization.Json;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Local operator boundary: same-origin loopback requests only; operator API mutations, ADK live sessions
 * and workflow-changing chat commands additionally require the page-issued operator token.
 */
final class LocalOperatorFilter extends OncePerRequestFilter {
    static final String TOKEN_HEADER = "X-Factory-Token";
    private static final String API_PREFIX = "/factory/api/";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    /** ADK endpoints whose request body carries a chat message routed to {@link OperatorCommands}. */
    private static final Set<String> CHAT_RUN_PATHS = Set.of("/run", "/run_sse");
    private static final String LIVE_PATH = "/run_live";
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;
    private final OperatorToken token;

    LocalOperatorFilter(OperatorToken token) { this.token = token; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!LOCAL_HOSTS.contains(request.getServerName()) || !sameOrigin(request.getHeader("Origin"), request)) {
            JsonErrors.write(response, HttpServletResponse.SC_FORBIDDEN, "Local same-origin requests only");
            return;
        }
        boolean authorized = token.matches(request.getHeader(TOKEN_HEADER));
        boolean mutation = !SAFE_METHODS.contains(request.getMethod());
        String path = request.getRequestURI();
        if (path.startsWith(API_PREFIX)) {
            if (mutation && !authorized) {
                JsonErrors.write(response, HttpServletResponse.SC_FORBIDDEN, "Reload the operator page before taking an action");
                return;
            }
        } else {
            if (path.equals(LIVE_PATH) && !authorized) {
                // Live socket messages cannot be inspected here, so the whole session needs the token.
                JsonErrors.write(response, HttpServletResponse.SC_FORBIDDEN, "Live chat sessions require the operator token");
                return;
            }
            if (mutation && (request.getContentType() == null || !request.getContentType().startsWith("application/json"))) {
                JsonErrors.write(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "JSON required");
                return;
            }
            if (mutation && !authorized && CHAT_RUN_PATHS.contains(path) && changesWorkflowState(request)) {
                JsonErrors.write(response, HttpServletResponse.SC_FORBIDDEN,
                    "Workflow commands require the operator token; use the operator page at /factory/");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** Reads the message exactly as the agent will. Unparseable bodies are treated as state-changing. */
    private static boolean changesWorkflowState(HttpServletRequest request) {
        if (!(request.getAttribute(RequestBodyLimit.BODY_ATTRIBUTE) instanceof byte[] body)) return true;
        try {
            JsonNode message = Json.MAPPER.readTree(body).path("newMessage");
            if (message.isMissingNode() || message.isNull()) return false;
            String text = Content.fromJson(message.toString()).text();
            return OperatorCommands.changesWorkflowState(text);
        } catch (IOException | GenAiIOException | IllegalArgumentException unreadable) {
            return true;
        }
    }

    private static boolean sameOrigin(String origin, HttpServletRequest request) {
        if (origin == null) return true;
        try {
            URI uri = URI.create(origin);
            int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? HTTPS_PORT : HTTP_PORT) : uri.getPort();
            return request.getScheme().equals(uri.getScheme()) && request.getServerName().equals(uri.getHost()) && request.getServerPort() == port;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }
}
