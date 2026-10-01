package dev.shortener.shorten;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.shortener.WebSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;

/** {@code POST /api/shorten} over HTTP: the response shape, and which requests consume quota. */
class ShortenLinkControllerTest extends WebSliceTest {
    private static final String INVALID_REQUEST = "invalid_request";
    private static final String NUMBERED_TARGET = "https://example.com/";

    private void storageAcceptsEveryLink() {
        when(links.create(anyString(), anyString())).thenReturn(LINK);
    }

    private void exhaustQuota() throws Exception {
        for (int i = 0; i < QUOTA; i++) {
            shorten(urlBody(NUMBERED_TARGET + i)).andExpect(status().isCreated());
        }
    }

    @Test
    void createsLinkWithConfiguredBaseUrl() throws Exception {
        storageAcceptsEveryLink();
        shorten(urlBody(TARGET))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(LINK.code()))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/" + LINK.code()));
    }

    @Test
    void storesTheCanonicalAliasTheClientAskedFor() throws Exception {
        storageAcceptsEveryLink();
        shorten(aliasBody(TARGET, "Mixed-Alias")).andExpect(status().isCreated());
        verify(links).create("mixed-alias", TARGET);
    }

    @Test
    void readingTheCreationEndpointIsMethodNotAllowedNotServerError() throws Exception {
        expectProblem(mvc.perform(get(SHORTEN)), 405, "method_not_allowed");
    }

    @Test
    void nonJsonBodyIsUnsupportedMediaType() throws Exception {
        expectProblem(shorten(MediaType.TEXT_PLAIN_VALUE, "https://example.com"), 415, "unsupported_media_type");
    }

    @Test
    void oversizedBodyIsRejectedBeforeItReachesTheUseCase() throws Exception {
        expectProblem(shorten(urlBody(NUMBERED_TARGET + "x".repeat(70_000))), 413, "request_too_large");
        verify(links, never()).create(anyString(), anyString());
    }

    @Test
    void quotaExhaustionReturnsRetryAfterAndStoresNothingMore() throws Exception {
        storageAcceptsEveryLink();
        exhaustQuota();
        var limited = shorten(urlBody("https://example.com/over"));
        expectProblem(limited, 429, "rate_limited");
        limited.andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]*")));
        verify(links, never()).create(anyString(), eq("https://example.com/over"));
    }

    @Test
    void invalidRequestsDoNotConsumeQuota() throws Exception {
        storageAcceptsEveryLink();
        for (int i = 0; i < QUOTA + 2; i++) {
            expectProblem(shorten(aliasBody("https://example.com", "x!")), 400, INVALID_REQUEST);
            expectProblem(shorten("not-json"), 400, INVALID_REQUEST);
            expectProblem(shorten(urlBody("http://localhost/private")), 400, INVALID_REQUEST);
            expectProblem(shorten(aliasBody("https://example.com", "Logout")), 400, INVALID_REQUEST);
        }
        exhaustQuota();
    }

    @Test
    void aliasConflictIsConflict() throws Exception {
        when(links.create("taken-alias", "https://example.com")).thenThrow(new DuplicateKeyException("taken"));
        expectProblem(shorten(aliasBody("https://example.com", "taken-alias")), 409, "alias_conflict");
    }

    @Test
    void repeatedCodeCollisionsAreReportedAsTemporarilyUnavailable() throws Exception {
        when(links.create(anyString(), anyString())).thenThrow(new DuplicateKeyException("collision"));
        var result = shorten(urlBody(TARGET));
        expectProblem(result, 503, "temporarily_unavailable");
        result.andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.detail").value("The service is temporarily unavailable"));
    }
}
