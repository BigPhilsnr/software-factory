package dev.softwarefactory.operator.security;

import dev.softwarefactory.platform.Json;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Filter-level errors use the same {@code {"error": ...}} body as controller errors. */
final class JsonErrors {
    private JsonErrors() {}

    static void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Json.MAPPER.writeValue(response.getOutputStream(), Map.of("error", message));
    }
}
