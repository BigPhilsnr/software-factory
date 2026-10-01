package dev.shortener.platform.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestBodyLimitTest {
    private static final int LIMIT = 64 * 1024;
    private static final String SHORTEN = "/api/shorten";
    private final RequestBodyLimit filter = new RequestBodyLimit(LIMIT);

    @Test
    void blocksOversizedBodiesBeforeJsonParsingWithAProblemDocument() throws Exception {
        var request = new MockHttpServletRequest("POST", SHORTEN);
        request.setContent(new byte[LIMIT + 1]);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("JSON parsing must not run"));
        assertEquals(413, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/problem+json"));
        assertTrue(response.getContentAsString().contains("\"error\":\"request_too_large\""));
    }

    @Test
    void blocksChunkedBodiesWithoutDeclaredLength() throws Exception {
        var request = new MockHttpServletRequest("POST", SHORTEN) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[LIMIT + 1]);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("JSON parsing must not run"));
        assertEquals(413, response.getStatus());
    }

    @Test
    void preservesSmallBodyForTheController() throws Exception {
        var request = new MockHttpServletRequest("POST", SHORTEN);
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) ->
                        assertEquals("{}", new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
    }

    @Test
    void ignoresBodylessMethods() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/urls/abcd/analytics");
        request.setContent(new byte[LIMIT + 1]);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        assertEquals(200, response.getStatus());
    }
}
