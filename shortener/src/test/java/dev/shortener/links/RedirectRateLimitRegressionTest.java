package dev.shortener.links;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.shortener.analytics.AnalyticsRecorder;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Seeded brownfield defect: an exhausted creation quota must never throttle redirects. */
@WebMvcTest(ShortenerController.class)
@Import(WebSliceConfiguration.class)
@TestPropertySource(properties = {WebSliceConfiguration.SMALL_QUOTA, WebSliceConfiguration.NO_DB_HEALTH})
class RedirectRateLimitRegressionTest {
    private static final int REDIRECTS = 35;
    private static final int QUOTA = 3;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ShortenerService service;

    @MockitoBean
    AnalyticsRecorder recorder;

    private ResultActions create() throws Exception {
        return mvc.perform(post("/api/shorten")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://example.com\"}"));
    }

    @Test
    void redirectsRemainAvailableAfterTheSamePeerExhaustsItsCreationQuota() throws Exception {
        Link link = new Link(1L, "abc12345", "https://example.com", Instant.EPOCH);
        when(service.find(link.code())).thenReturn(Optional.of(link));
        when(service.prepare(anyString(), any())).thenReturn(new LinkDraft(link.targetUrl(), null));
        when(service.create(any())).thenReturn(link);

        for (int i = 0; i < QUOTA; i++) create().andExpect(status().isCreated());
        create().andExpect(status().isTooManyRequests());

        for (int i = 0; i < REDIRECTS; i++) {
            mvc.perform(get("/" + link.code())).andExpect(status().isFound());
        }
    }
}
