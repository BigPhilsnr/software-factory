package dev.shortener.redirect;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.shortener.WebSliceTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Seeded brownfield defect: an exhausted creation quota must never throttle redirects. */
class RedirectRateLimitRegressionTest extends WebSliceTest {
    private static final int REDIRECTS = 35;

    @Test
    void redirectsRemainAvailableAfterTheSamePeerExhaustsItsCreationQuota() throws Exception {
        when(links.findByCode(LINK.code())).thenReturn(Optional.of(LINK));
        when(links.create(anyString(), anyString())).thenReturn(LINK);

        for (int i = 0; i < QUOTA; i++) shorten(urlBody(TARGET)).andExpect(status().isCreated());
        shorten(urlBody(TARGET)).andExpect(status().isTooManyRequests());

        for (int i = 0; i < REDIRECTS; i++) {
            mvc.perform(get("/" + LINK.code())).andExpect(status().isFound());
        }
        verify(visits, times(REDIRECTS)).record(LINK.id());
    }
}
