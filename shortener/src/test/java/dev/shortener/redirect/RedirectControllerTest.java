package dev.shortener.redirect;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.shortener.WebSliceTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@code GET} and {@code HEAD /{code}} over HTTP: where the visitor is sent and when the visit counts. */
class RedirectControllerTest extends WebSliceTest {
    private static final String LOCATION = "Location";

    @Test
    void redirectIsUncacheableAndCounted() throws Exception {
        when(links.findByCode(LINK.code())).thenReturn(Optional.of(LINK));
        mvc.perform(get("/" + LINK.code()))
                .andExpect(status().isFound())
                .andExpect(header().string(LOCATION, LINK.targetUrl()))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        verify(visits).record(LINK.id());
    }

    @Test
    void anySpellingOfTheCodeFollowsTheSameLink() throws Exception {
        when(links.findByCode(LINK.code())).thenReturn(Optional.of(LINK));
        mvc.perform(get("/ABCD1234"))
                .andExpect(status().isFound())
                .andExpect(header().string(LOCATION, LINK.targetUrl()));
    }

    @Test
    void headPreviewsWithoutCounting() throws Exception {
        when(links.findByCode(LINK.code())).thenReturn(Optional.of(LINK));
        mvc.perform(head("/" + LINK.code()))
                .andExpect(status().isFound())
                .andExpect(header().string(LOCATION, LINK.targetUrl()));
        verify(visits, never()).record(LINK.id());
    }

    @Test
    void unknownCodeIsNotFoundAndNotRecorded() throws Exception {
        when(links.findByCode("missing1")).thenReturn(Optional.empty());
        expectProblem(mvc.perform(get("/missing1")), 404, "not_found");
        verify(visits, never()).record(anyLong());
    }

    @Test
    void postingToACodeIsMethodNotAllowedNotServerError() throws Exception {
        expectProblem(mvc.perform(post("/" + LINK.code())), 405, "method_not_allowed");
    }
}
