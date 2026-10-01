package dev.shortener.analytics;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.shortener.WebSliceTest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@code GET /api/urls/{code}/analytics} over HTTP. */
class LinkAnalyticsControllerTest extends WebSliceTest {
    private static final String ANALYTICS = "/api/urls/" + LINK.code() + "/analytics";
    private static final String NOT_FOUND = "not_found";

    @Test
    void reportsZeroRedirectsAndNoTimestampBeforeTheFirstVisit() throws Exception {
        when(links.findByCode(LINK.code())).thenReturn(Optional.of(LINK));
        when(stats.forLink(LINK.id())).thenReturn(Optional.of(new RedirectStats(0, null)));
        mvc.perform(get(ANALYTICS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(LINK.code()))
                .andExpect(jsonPath("$.redirectCount").value(0))
                .andExpect(jsonPath("$.lastRedirectAt").doesNotExist());
    }

    @Test
    void reportsRecordedRedirectsWithoutCountingTheLookupAsAVisit() throws Exception {
        Instant last = Instant.parse("2026-10-01T12:00:00Z");
        when(links.findByCode(LINK.code())).thenReturn(Optional.of(LINK));
        when(stats.forLink(LINK.id())).thenReturn(Optional.of(new RedirectStats(3, last)));
        mvc.perform(get("/api/urls/ABCD1234/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(LINK.code()))
                .andExpect(jsonPath("$.redirectCount").value(3))
                .andExpect(jsonPath("$.lastRedirectAt").value(last.toString()));
        verify(visits, never()).record(anyLong());
    }

    @Test
    void unknownCodeIsNotFound() throws Exception {
        expectProblem(mvc.perform(get("/api/urls/missing1/analytics")), 404, NOT_FOUND);
        verify(stats, never()).forLink(anyLong());
    }

    @Test
    void missingStatisticsForAMissingLinkIsNotFound() throws Exception {
        when(links.findByCode(LINK.code())).thenReturn(Optional.of(LINK));
        when(stats.forLink(LINK.id())).thenReturn(Optional.empty());
        expectProblem(mvc.perform(get(ANALYTICS)), 404, NOT_FOUND);
    }
}
