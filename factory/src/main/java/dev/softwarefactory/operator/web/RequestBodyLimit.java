package dev.softwarefactory.operator.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects both declared and chunked oversized bodies before JSON deserialization. The buffered body is
 * exposed as a request attribute so the operator boundary can inspect chat commands without consuming it.
 */
public final class RequestBodyLimit extends OncePerRequestFilter {
    static final int MAX_BYTES = 64 * 1024;
    static final String BODY_ATTRIBUTE = RequestBodyLimit.class.getName() + ".body";
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!BODY_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > MAX_BYTES) {
            JsonErrors.write(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "request_too_large");
            return;
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            JsonErrors.write(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "request_too_large");
            return;
        }
        request.setAttribute(BODY_ATTRIBUTE, bytes);
        chain.doFilter(new BufferedRequest(request, bytes), response);
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final ByteArrayInputStream input;

        BufferedRequest(HttpServletRequest request, byte[] bytes) {
            super(request);
            this.input = new ByteArrayInputStream(bytes);
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return input.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Synchronous JSON input");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
