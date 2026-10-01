package dev.shortener.links;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.shortener.analytics.AnalyticsRecorder;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** HTTP edge behaviour: status mapping, problem bodies, security scoping, filters and quota placement. */
@WebMvcTest(ShortenerController.class)
@Import(WebSliceConfiguration.class)
@TestPropertySource(properties = {WebSliceConfiguration.SMALL_QUOTA, WebSliceConfiguration.NO_DB_HEALTH})
class ShortenerWebSliceTest {
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final AtomicInteger PEERS = new AtomicInteger();
    private static final Link LINK = new Link(7, "abcd1234", "https://example.com/target", Instant.EPOCH);

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ShortenerService service;

    @MockitoBean
    AnalyticsRecorder recorder;

    /** The limiter lives in the shared context, so each test creates links from its own peer address. */
    private final String peer = "192.0.2." + PEERS.incrementAndGet();

    private ResultActions shorten(String body) throws Exception {
        return mvc.perform(post("/api/shorten")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(request -> {
                    request.setRemoteAddr(peer);
                    return request;
                }));
    }

    private void acceptAnyValidCreation() {
        when(service.prepare(anyString(), any())).thenAnswer(call -> new LinkDraft(call.getArgument(0), null));
        when(service.create(any())).thenReturn(LINK);
    }

    private static void expectProblem(ResultActions result, int status, String error) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(header().string("Content-Type", containsString(PROBLEM_JSON)))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.error").value(error));
    }

    @Test
    void createsLinkWithConfiguredBaseUrl() throws Exception {
        acceptAnyValidCreation();
        shorten("{\"url\":\"https://example.com/target\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(LINK.code()))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/" + LINK.code()));
    }

    @Test
    void wrongMethodIsMethodNotAllowedNotServerError() throws Exception {
        expectProblem(mvc.perform(get("/api/shorten")), 405, "method_not_allowed");
        expectProblem(mvc.perform(post("/" + LINK.code())), 405, "method_not_allowed");
    }

    @Test
    void nonJsonBodyIsUnsupportedMediaType() throws Exception {
        expectProblem(
                mvc.perform(
                        post("/api/shorten").contentType(MediaType.TEXT_PLAIN).content("https://example.com")),
                415,
                "unsupported_media_type");
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        expectProblem(mvc.perform(get("/no/such/path")), 404, "not_found");
    }

    @Test
    void unknownCodeIsNotFoundAndNotRecorded() throws Exception {
        when(service.find("missing1")).thenReturn(Optional.empty());
        expectProblem(mvc.perform(get("/missing1")), 404, "not_found");
        verify(recorder, never()).record(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void logoutIsAnOrdinaryCodeNotASecurityEndpoint() throws Exception {
        when(service.find("logout")).thenReturn(Optional.empty());
        expectProblem(mvc.perform(get("/logout")), 404, "not_found");
        expectProblem(mvc.perform(post("/logout")), 405, "method_not_allowed");
        verify(service).find("logout");
    }

    @Test
    void redirectIsUncacheableAndCounted() throws Exception {
        when(service.find(LINK.code())).thenReturn(Optional.of(LINK));
        mvc.perform(get("/" + LINK.code()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LINK.targetUrl()))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        verify(recorder).record(LINK.id());
    }

    @Test
    void headPreviewsWithoutCounting() throws Exception {
        when(service.find(LINK.code())).thenReturn(Optional.of(LINK));
        mvc.perform(head("/" + LINK.code()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LINK.targetUrl()));
        verify(recorder, never()).record(LINK.id());
    }

    @Test
    void managementInternalsAreDeniedButHealthAndInfoArePublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isForbidden());
    }

    @Test
    void oversizedBodyIsRejectedBeforeParsing() throws Exception {
        expectProblem(
                shorten("{\"url\":\"https://example.com/" + "x".repeat(70_000) + "\"}"), 413, "request_too_large");
        verify(service, never()).prepare(anyString(), any());
    }

    @Test
    void quotaExhaustionReturnsRetryAfter() throws Exception {
        acceptAnyValidCreation();
        for (int i = 0; i < 3; i++)
            shorten("{\"url\":\"https://example.com/" + i + "\"}").andExpect(status().isCreated());
        var limited = shorten("{\"url\":\"https://example.com/over\"}");
        expectProblem(limited, 429, "rate_limited");
        limited.andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]*")));
    }

    @Test
    void invalidRequestsDoNotConsumeQuota() throws Exception {
        acceptAnyValidCreation();
        when(service.prepare(eq("https://bad.invalid-target"), isNull()))
                .thenThrow(new InvalidLinkException("Target rejected"));
        for (int i = 0; i < 5; i++) {
            expectProblem(shorten("{\"url\":\"https://example.com\",\"alias\":\"x!\"}"), 400, "invalid_request");
            expectProblem(shorten("not-json"), 400, "invalid_request");
            expectProblem(shorten("{\"url\":\"https://bad.invalid-target\"}"), 400, "invalid_request");
        }
        for (int i = 0; i < 3; i++)
            shorten("{\"url\":\"https://example.com/" + i + "\"}").andExpect(status().isCreated());
    }

    @Test
    void aliasConflictIsConflict() throws Exception {
        when(service.prepare(anyString(), any())).thenReturn(new LinkDraft("https://example.com", "taken-alias"));
        when(service.create(any())).thenThrow(new ShortenerService.AliasConflictException(null));
        expectProblem(shorten("{\"url\":\"https://example.com\",\"alias\":\"taken-alias\"}"), 409, "alias_conflict");
    }

    @Test
    void transientDatabaseFailureIsUnavailableWithRetryAfter() throws Exception {
        when(service.find(LINK.code())).thenThrow(new CannotGetJdbcConnectionException("pool exhausted"));
        var result = mvc.perform(get("/" + LINK.code()));
        expectProblem(result, 503, "temporarily_unavailable");
        result.andExpect(header().string("Retry-After", "5"));
    }

    @Test
    void permanentDatabaseAndProgrammingFailuresAreServerErrors() throws Exception {
        when(service.find("broken01"))
                .thenThrow(new BadSqlGrammarException("find", "SELECT", new java.sql.SQLException("bad")));
        when(service.find("broken02")).thenThrow(new IllegalStateException("bug"));
        expectProblem(mvc.perform(get("/broken01")), 500, "internal_error");
        expectProblem(mvc.perform(get("/broken02")), 500, "internal_error");
    }

    @Test
    void propagatesWellFormedRequestIdAndReplacesUnsafeOnes() throws Exception {
        when(service.find(anyString())).thenReturn(Optional.empty());
        mvc.perform(get("/abcd0000").header("X-Request-Id", "trace-123"))
                .andExpect(header().string("X-Request-Id", "trace-123"));
        mvc.perform(get("/abcd0000").header("X-Request-Id", "bad id\r\ninjected"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }
}
