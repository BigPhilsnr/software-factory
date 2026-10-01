package dev.shortener.platform.security;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.shortener.WebSliceTest;
import org.junit.jupiter.api.Test;

/** The security chain guards the management surface and stays out of the way of everything else. */
class PublicApiSecurityTest extends WebSliceTest {
    private static final String REQUEST_ID = "X-Request-Id";

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
    void logoutIsAnOrdinaryReservedCodeNotASecurityEndpoint() throws Exception {
        expectProblem(mvc.perform(get("/logout")), 404, "not_found");
        expectProblem(mvc.perform(post("/logout")), 405, "method_not_allowed");
        verify(links, never()).findByCode(anyString());
    }

    @Test
    void requestIdsAreCorrelatedEvenOnPathsTheChainDoesNotGuard() throws Exception {
        mvc.perform(get("/abcd0000").header(REQUEST_ID, "trace-123"))
                .andExpect(header().string(REQUEST_ID, "trace-123"));
        mvc.perform(get("/abcd0000").header(REQUEST_ID, "bad id\r\ninjected"))
                .andExpect(header().string(REQUEST_ID, matchesPattern("[0-9a-f-]{36}")));
        mvc.perform(get("/actuator/env").header(REQUEST_ID, "trace-456"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(REQUEST_ID, "trace-456"));
    }
}
