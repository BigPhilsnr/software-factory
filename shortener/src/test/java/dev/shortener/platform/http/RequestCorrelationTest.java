package dev.shortener.platform.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationTest {
    private final RequestCorrelation filter = new RequestCorrelation();

    /** Runs one request through the filter and returns the id that log lines carried while it was handled. */
    private String idLoggedWhileHandling(String supplied, MockHttpServletResponse response) throws Exception {
        var request = new MockHttpServletRequest("GET", "/abcd0000");
        if (supplied != null) request.addHeader(RequestCorrelation.HEADER, supplied);
        var logged = new AtomicReference<String>();
        filter.doFilter(request, response, (req, res) -> logged.set(MDC.get(RequestCorrelation.MDC_KEY)));
        return logged.get();
    }

    @Test
    void propagatesWellFormedRequestIdToTheResponseAndTheLogContext() throws Exception {
        var response = new MockHttpServletResponse();
        assertEquals("trace-123", idLoggedWhileHandling("trace-123", response));
        assertEquals("trace-123", response.getHeader(RequestCorrelation.HEADER));
        assertNull(MDC.get(RequestCorrelation.MDC_KEY), "the id must not leak into the next request on this thread");
    }

    @Test
    void replacesUnsafeOrMissingRequestIdsWithAGeneratedOne() throws Exception {
        for (String unsafe : new String[] {"bad id\r\ninjected", "x".repeat(65), "", null}) {
            var response = new MockHttpServletResponse();
            String logged = idLoggedWhileHandling(unsafe, response);
            assertTrue(logged.matches("[0-9a-f-]{36}"), "generated for: " + unsafe);
            assertEquals(logged, response.getHeader(RequestCorrelation.HEADER));
        }
    }
}
