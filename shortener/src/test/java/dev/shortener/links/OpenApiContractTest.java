package dev.shortener.links;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import dev.shortener.analytics.AnalyticsRecorder;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Requests and responses of the real MVC stack must match openapi.yaml, and every endpoint must be documented. */
@WebMvcTest(ShortenerController.class)
@Import(WebSliceConfiguration.class)
@TestPropertySource(properties = {WebSliceConfiguration.SMALL_QUOTA, WebSliceConfiguration.NO_DB_HEALTH})
class OpenApiContractTest {
    private static final AtomicInteger PEERS = new AtomicInteger();
    private static final Link LINK = new Link(7, "abcd1234", "https://example.com/target", Instant.EPOCH);
    private static String specification;
    private static OpenApiInteractionValidator validator;

    @Autowired MockMvc mvc;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @MockitoBean ShortenerService service;
    @MockitoBean AnalyticsRecorder recorder;
    private final String peer = "198.51.100." + PEERS.incrementAndGet();

    /** Located from the compiled classes so it works whatever the Maven working directory is. */
    @BeforeAll static void loadSpecification() throws URISyntaxException {
        Path classes = Path.of(OpenApiContractTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        specification = classes.resolve("../../openapi.yaml").normalize().toUri().toString();
        validator = OpenApiInteractionValidator.createForSpecificationUrl(specification).build();
    }

    private static ResultMatcher conforms() { return openApi().isValid(validator); }

    /** For requests deliberately outside the contract: only the named request rules are relaxed. */
    private static ResultMatcher respondsPerContractTo(String... ignoredRequestRules) {
        var levels = LevelResolver.create();
        for (String rule : ignoredRequestRules) levels.withLevel(rule, ValidationReport.Level.IGNORE);
        return openApi().isValid(OpenApiInteractionValidator.createForSpecificationUrl(specification)
            .withLevelResolver(levels.build()).build());
    }

    private ResultActions shorten(String contentType, String body) throws Exception {
        return mvc.perform(post("/api/shorten").contentType(contentType).content(body).with(request -> {
            request.setRemoteAddr(peer);
            return request;
        }));
    }

    private ResultActions shorten(String body) throws Exception { return shorten(MediaType.APPLICATION_JSON_VALUE, body); }

    @Test void creation() throws Exception {
        when(service.prepare(anyString(), any())).thenReturn(new LinkDraft(LINK.targetUrl(), null));
        when(service.create(any())).thenReturn(LINK);
        shorten("{\"url\":\"https://example.com/target\"}").andExpect(status().isCreated()).andExpect(conforms());
        shorten("{\"url\":\"https://example.com/target\"}").andExpect(status().isCreated());
        shorten("{\"url\":\"https://example.com/target\"}").andExpect(status().isCreated());
        shorten("{\"url\":\"https://example.com/target\"}").andExpect(status().isTooManyRequests()).andExpect(conforms());
    }

    @Test void creationFailures() throws Exception {
        when(service.prepare(anyString(), any())).thenReturn(new LinkDraft(LINK.targetUrl(), "taken-alias"));
        when(service.create(any())).thenThrow(new ShortenerService.AliasConflictException(null));
        shorten("{\"url\":\"https://example.com\",\"alias\":\"taken-alias\"}").andExpect(status().isConflict()).andExpect(conforms());
        when(service.prepare(anyString(), any())).thenThrow(new InvalidLinkException("Target rejected"));
        shorten("{\"url\":\"http://localhost/x\"}").andExpect(status().isBadRequest()).andExpect(conforms());
        shorten("{\"url\":\"https://example.com/" + "x".repeat(70_000) + "\"}").andExpect(status().is(413))
            .andExpect(respondsPerContractTo("validation.request.body.schema.maxLength"));
    }

    @Test void unsupportedMediaTypeIsDocumented() throws Exception {
        shorten(MediaType.TEXT_PLAIN_VALUE, "https://example.com").andExpect(status().isUnsupportedMediaType())
            .andExpect(respondsPerContractTo("validation.request.contentType.notAllowed"));
    }

    @Test void redirectsAndPreview() throws Exception {
        when(service.find(LINK.code())).thenReturn(Optional.of(LINK));
        mvc.perform(get("/" + LINK.code())).andExpect(status().isFound()).andExpect(conforms());
        mvc.perform(head("/" + LINK.code())).andExpect(status().isFound()).andExpect(conforms());
        mvc.perform(get("/missing1")).andExpect(status().isNotFound()).andExpect(conforms());
        when(service.find("down0001")).thenThrow(new CannotGetJdbcConnectionException("pool exhausted"));
        mvc.perform(get("/down0001")).andExpect(status().isServiceUnavailable()).andExpect(conforms());
        when(service.find("bug00001")).thenThrow(new IllegalStateException("bug"));
        mvc.perform(get("/bug00001")).andExpect(status().isInternalServerError()).andExpect(conforms());
    }

    @Test void analytics() throws Exception {
        when(service.find(LINK.code())).thenReturn(Optional.of(LINK));
        when(service.statistics(LINK)).thenReturn(new RedirectStats(0, null));
        mvc.perform(get("/api/urls/" + LINK.code() + "/analytics")).andExpect(status().isOk()).andExpect(conforms());
        when(service.statistics(LINK)).thenReturn(new RedirectStats(3, Instant.parse("2026-10-01T12:00:00Z")));
        mvc.perform(get("/api/urls/" + LINK.code() + "/analytics")).andExpect(status().isOk()).andExpect(conforms());
        mvc.perform(get("/api/urls/missing1/analytics")).andExpect(status().isNotFound()).andExpect(conforms());
    }

    @Test void everyControllerEndpointIsDocumented() {
        OpenAPI api = new OpenAPIV3Parser().read(specification);
        assertNotNull(api, "openapi.yaml must parse");
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().equals(ShortenerController.class)) return;
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                PathItem path = api.getPaths().get(pattern);
                assertNotNull(path, "undocumented path " + pattern);
                info.getMethodsCondition().getMethods().forEach(method -> assertTrue(
                    path.readOperationsMap().containsKey(PathItem.HttpMethod.valueOf(method.name().toUpperCase(Locale.ROOT))),
                    "undocumented " + method + " " + pattern));
            }
        });
    }
}
