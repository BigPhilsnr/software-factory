package dev.shortener;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.shortener.analytics.RedirectStatsReader;
import dev.shortener.link.Link;
import dev.shortener.link.LinkRepository;
import dev.shortener.redirect.VisitRecorder;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The whole HTTP edge over MockMvc: real controllers, use cases, error mapping, security chain, filters and
 * creation limiter. Only the three ports that leave the process are mocks: link storage, the visit recorder
 * and the statistics reader. Every slice test shares this one application context.
 */
@WebMvcTest(includeFilters = @ComponentScan.Filter(Service.class))
@Import(WebSliceConfiguration.class)
@TestPropertySource(
        properties = {
            "shortener.rate-limit.requests-per-window=" + WebSliceTest.QUOTA,
            // No database in a slice, so the readiness group's "db" member is absent.
            "management.endpoint.health.validate-group-membership=false"
        })
public abstract class WebSliceTest {
    protected static final int QUOTA = 3;
    protected static final String TARGET = "https://example.com/target";
    protected static final Link LINK = new Link(7, "abcd1234", TARGET, Instant.EPOCH);
    protected static final String SHORTEN = "/api/shorten";

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final AtomicInteger PEERS = new AtomicInteger();

    @Autowired
    protected MockMvc mvc;

    @MockitoBean
    protected LinkRepository links;

    @MockitoBean
    protected VisitRecorder visits;

    @MockitoBean
    protected RedirectStatsReader stats;

    /** The limiter lives in the shared context, so each test creates links from its own peer address. */
    private final String peer = "192.0.2." + PEERS.incrementAndGet();

    protected ResultActions shorten(String body) throws Exception {
        return shorten(MediaType.APPLICATION_JSON_VALUE, body);
    }

    protected ResultActions shorten(String contentType, String body) throws Exception {
        return mvc.perform(post(SHORTEN).contentType(contentType).content(body).with(request -> {
            request.setRemoteAddr(peer);
            return request;
        }));
    }

    protected static String urlBody(String url) {
        return "{\"url\":\"" + url + "\"}";
    }

    protected static String aliasBody(String url, String alias) {
        return "{\"url\":\"" + url + "\",\"alias\":\"" + alias + "\"}";
    }

    protected static void expectProblem(ResultActions result, int status, String error) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(header().string("Content-Type", containsString(PROBLEM_JSON)))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.error").value(error));
    }
}
