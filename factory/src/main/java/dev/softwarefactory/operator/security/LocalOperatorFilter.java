package dev.softwarefactory.operator.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.Content;
import dev.softwarefactory.platform.Json;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Local operator boundary: same-origin loopback requests only; operator API mutations, ADK live sessions
 * and workflow-changing chat commands additionally require the page-issued operator token.
 */
public final class LocalOperatorFilter extends OncePerRequestFilter {
    static final String TOKEN_HEADER = "X-Factory-Token";
    private static final String API_PREFIX = "/factory/api/";

    /** The loopback names a browser can use for this server; the server itself binds to loopback only. */
    @SuppressWarnings(
            "PMD.AvoidUsingHardCodedIP") // Loopback literals are the definition of "local", not a deployment address.
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    /** ADK endpoints whose request body carries a chat message routed to the operator command router. */
    private static final Set<String> CHAT_RUN_PATHS = Set.of("/run", "/run_sse");

    private static final String LIVE_PATH = "/run_live";
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;
    private final OperatorToken token;
    private final Predicate<String> changesWorkflowState;

    /**
     * @param changesWorkflowState whether a chat message is a command that creates, advances or decides a run
     */
    public LocalOperatorFilter(OperatorToken token, Predicate<String> changesWorkflowState) {
        this.token = token;
        this.changesWorkflowState = changesWorkflowState;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Refusal refusal = refusal(request);
        if (refusal != null) {
            JsonErrors.write(response, refusal.status(), refusal.message());
            return;
        }
        chain.doFilter(request, response);
    }

    /** Why the request may not pass, or null when it may. */
    private Refusal refusal(HttpServletRequest request) {
        if (!LOCAL_HOSTS.contains(request.getServerName()) || !sameOrigin(request.getHeader("Origin"), request)) {
            return new Refusal(HttpServletResponse.SC_FORBIDDEN, "Local same-origin requests only");
        }
        boolean authorized = token.matches(request.getHeader(TOKEN_HEADER));
        boolean mutation = !SAFE_METHODS.contains(request.getMethod());
        String path = request.getRequestURI();
        if (path.startsWith(API_PREFIX)) {
            return mutation && !authorized
                    ? new Refusal(HttpServletResponse.SC_FORBIDDEN, "Reload the operator page before taking an action")
                    : null;
        }
        return chatRefusal(request, path, mutation, authorized);
    }

    /** ADK endpoints: live sessions and workflow commands need the token; other chat only needs JSON. */
    private Refusal chatRefusal(HttpServletRequest request, String path, boolean mutation, boolean authorized) {
        if (LIVE_PATH.equals(path) && !authorized) {
            // Live socket messages cannot be inspected here, so the whole session needs the token.
            return new Refusal(HttpServletResponse.SC_FORBIDDEN, "Live chat sessions require the operator token");
        }
        if (!mutation) return null;
        if (request.getContentType() == null || !request.getContentType().startsWith("application/json")) {
            return new Refusal(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "JSON required");
        }
        if (!authorized && CHAT_RUN_PATHS.contains(path) && changesWorkflowState(request)) {
            return new Refusal(
                    HttpServletResponse.SC_FORBIDDEN,
                    "Workflow commands require the operator token; use the operator page at /factory/");
        }
        return null;
    }

    /** Reads the message exactly as the agent will. Unparseable bodies are treated as state-changing. */
    private boolean changesWorkflowState(HttpServletRequest request) {
        if (!(request.getAttribute(RequestBodyLimit.BODY_ATTRIBUTE) instanceof byte[] body)) return true;
        try {
            JsonNode message = Json.MAPPER.readTree(body).path("newMessage");
            if (message.isMissingNode() || message.isNull()) return false;
            String text = Content.fromJson(message.toString()).text();
            return changesWorkflowState.test(text);
        } catch (IOException | GenAiIOException | IllegalArgumentException unreadable) {
            return true;
        }
    }

    private record Refusal(int status, String message) {}

    private static boolean sameOrigin(String origin, HttpServletRequest request) {
        if (origin == null) return true;
        try {
            URI uri = URI.create(origin);
            int port = uri.getPort() < 0 ? defaultPort(uri.getScheme()) : uri.getPort();
            return request.getScheme().equals(uri.getScheme())
                    && request.getServerName().equals(uri.getHost())
                    && request.getServerPort() == port;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static int defaultPort(String scheme) {
        return "https".equals(scheme) ? HTTPS_PORT : HTTP_PORT;
    }
}
