package dev.shortener.platform.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Propagates a caller-supplied {@value #HEADER} (when well-formed) or a generated one into the logging MDC
 * and echoes it on the response, so log lines can be joined across services.
 */
final class RequestCorrelation extends OncePerRequestFilter {
    static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";
    /** Rejects log-injection and unbounded values from untrusted callers. */
    private static final Pattern ACCEPTED_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = requestId(request.getHeader(HEADER));
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Echoes only text the pattern matched, so no caller-controlled CR/LF can ever reach a header or log. */
    private static String requestId(String supplied) {
        if (supplied != null) {
            Matcher accepted = ACCEPTED_ID.matcher(supplied);
            if (accepted.matches()) return accepted.group();
        }
        return UUID.randomUUID().toString();
    }
}
