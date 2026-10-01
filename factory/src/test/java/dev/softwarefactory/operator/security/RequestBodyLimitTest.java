package dev.softwarefactory.operator.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestBodyLimitTest {
    private final RequestBodyLimit filter = new RequestBodyLimit();

    @Test
    void bodiesAreBufferedOnceForTheBoundaryAndStillReadableDownstream() throws Exception {
        byte[] body = "{\"action\":\"advance\"}".getBytes(StandardCharsets.UTF_8);
        var request = new MockHttpServletRequest("POST", "/factory/api/runs/1/actions");
        request.setContent(body);
        var downstream = new AtomicReference<HttpServletRequest>();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> downstream.set((HttpServletRequest) req));

        assertArrayEquals(body, (byte[]) request.getAttribute(RequestBodyLimit.BODY_ATTRIBUTE));
        var input = downstream.get().getInputStream();
        assertTrue(input.isReady());
        assertFalse(input.isFinished());
        assertEquals('{', input.read());
        assertArrayEquals("\"action\"".getBytes(StandardCharsets.UTF_8), input.readNBytes(8));
        input.readAllBytes();
        assertTrue(input.isFinished());
        assertThrows(UnsupportedOperationException.class, () -> input.setReadListener(null));
    }

    @Test
    void bufferedBodiesCanBeReadAsCharacters() throws Exception {
        var request = new MockHttpServletRequest("PUT", "/run");
        request.setContent("{\"text\":\"héllo\"}".getBytes(StandardCharsets.UTF_8));
        var downstream = new AtomicReference<HttpServletRequest>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> downstream.set((HttpServletRequest) req));
        assertEquals("{\"text\":\"héllo\"}", downstream.get().getReader().readLine());
    }

    @Test
    void declaredAndUndeclaredOversizedBodiesAreRefusedBeforeAnythingReadsThem() throws Exception {
        byte[] oversized = new byte[RequestBodyLimit.MAX_BYTES + 1];
        for (boolean declared : new boolean[] {true, false}) {
            var request = new MockHttpServletRequest("POST", "/run") {
                @Override
                public long getContentLengthLong() {
                    return declared ? oversized.length : -1;
                }
            };
            request.setContent(oversized);
            var response = new MockHttpServletResponse();
            var reached = new AtomicReference<Object>();
            filter.doFilter(request, response, (req, res) -> reached.set(req));
            assertEquals(413, response.getStatus());
            assertEquals("{\"error\":\"request_too_large\"}", response.getContentAsString());
            assertTrue(response.getContentType().startsWith("application/json"));
            assertNull(reached.get());
        }
    }

    @Test
    void requestsWithoutABodyPassThroughUntouched() throws Exception {
        var request = new MockHttpServletRequest("GET", "/factory/api/runs");
        var downstream = new AtomicReference<Object>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> downstream.set(req));
        assertEquals(request, downstream.get());
        assertNull(request.getAttribute(RequestBodyLimit.BODY_ATTRIBUTE));
    }

    @Test
    void operatorTokensMatchOnlyTheExactValue() {
        var token = new OperatorToken("page-issued-token");
        assertTrue(token.matches("page-issued-token"));
        assertFalse(token.matches("page-issued-toke"));
        assertFalse(token.matches(null));
    }
}
