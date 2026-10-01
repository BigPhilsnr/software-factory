package dev.shortener;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import dev.shortener.analytics.RedirectStats;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Requests and responses of the real MVC stack must match openapi.yaml, and every endpoint must be documented. */
class OpenApiContractTest extends WebSliceTest {
    private static final String ANALYTICS = "/api/urls/" + LINK.code() + "/analytics";
    private static final int DOCUMENTED_OPERATIONS = 4;
    private static String specification;
    private static OpenApiInteractionValidator validator;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    /** Located from the compiled classes so it works whatever the Maven working directory is. */
    @BeforeAll
    static void loadSpecification() throws URISyntaxException {
        Path classes = Path.of(OpenApiContractTest.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        specification =
                classes.resolve("../../openapi.yaml").normalize().toUri().toString();
        validator = OpenApiInteractionValidator.createForSpecificationUrl(specification)
                .build();
    }

    private static ResultMatcher conforms() {
        return openApi().isValid(validator);
    }

    /** For requests deliberately outside the contract: only the named request rules are relaxed. */
    private static ResultMatcher respondsPerContractTo(String... ignoredRequestRules) {
        var levels = LevelResolver.create();
        for (String rule : ignoredRequestRules) levels.withLevel(rule, ValidationReport.Level.IGNORE);
        return openApi()
                .isValid(OpenApiInteractionValidator.createForSpecificationUrl(specification)
                        .withLevelResolver(levels.build())
                        .build());
    }

    @Test
    void shorteningALinkAndExhaustingTheQuotaFollowTheContract() throws Exception {
        when(links.create(anyString(), anyString())).thenReturn(LINK);
        shorten(urlBody(TARGET)).andExpect(status().isCreated()).andExpect(conforms());
        for (int created = 1; created < QUOTA; created++) {
            shorten(urlBody(TARGET)).andExpect(status().isCreated());
        }
        shorten(urlBody(TARGET)).andExpect(status().isTooManyRequests()).andExpect(conforms());
    }

    @Test
    void rejectedShorteningRequestsFollowTheContract() throws Exception {
        when(links.create(anyString(), anyString())).thenThrow(new DuplicateKeyException("taken"));
        shorten(aliasBody("https://example.com", "taken-alias"))
                .andExpect(status().isConflict())
                .andExpect(conforms());
        shorten(urlBody("http://localhost/x"))
                .andExpect(status().isBadRequest())
                .andExpect(conforms());
        shorten(urlBody("https://example.com/" + "x".repeat(70_000)))
                .andExpect(status().is(413))
                .andExpect(respondsPerContractTo("validation.request.body.schema.maxLength"));
    }

    @Test
    void unsupportedMediaTypeIsDocumented() throws Exception {
        shorten(MediaType.TEXT_PLAIN_VALUE, "https://example.com")
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(respondsPerContractTo("validation.request.contentType.notAllowed"));
    }

    @Test
    void redirectsPreviewsAndTheirFailuresFollowTheContract() throws Exception {
        when(links.findByCode(LINK.code())).thenReturn(Optional.of(LINK));
        mvc.perform(get("/" + LINK.code())).andExpect(status().isFound()).andExpect(conforms());
        mvc.perform(head("/" + LINK.code())).andExpect(status().isFound()).andExpect(conforms());
        mvc.perform(get("/missing1")).andExpect(status().isNotFound()).andExpect(conforms());
        when(links.findByCode("down0001")).thenThrow(new CannotGetJdbcConnectionException("pool exhausted"));
        mvc.perform(get("/down0001")).andExpect(status().isServiceUnavailable()).andExpect(conforms());
        when(links.findByCode("bug00001")).thenThrow(new IllegalStateException("bug"));
        mvc.perform(get("/bug00001"))
                .andExpect(status().isInternalServerError())
                .andExpect(conforms());
    }

    @Test
    void analyticsBeforeAndAfterTheFirstRedirectFollowTheContract() throws Exception {
        when(links.findByCode(LINK.code())).thenReturn(Optional.of(LINK));
        when(stats.forLink(LINK.id())).thenReturn(Optional.of(new RedirectStats(0, null)));
        mvc.perform(get(ANALYTICS)).andExpect(status().isOk()).andExpect(conforms());
        when(stats.forLink(LINK.id()))
                .thenReturn(Optional.of(new RedirectStats(3, Instant.parse("2026-10-01T12:00:00Z"))));
        mvc.perform(get(ANALYTICS)).andExpect(status().isOk()).andExpect(conforms());
        mvc.perform(get("/api/urls/missing1/analytics"))
                .andExpect(status().isNotFound())
                .andExpect(conforms());
    }

    @Test
    void everyControllerEndpointIsDocumented() {
        OpenAPI api = new OpenAPIV3Parser().read(specification);
        assertNotNull(api, "openapi.yaml must parse");
        var operations = new AtomicInteger();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("dev.shortener.")) return;
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                PathItem path = api.getPaths().get(pattern);
                assertNotNull(path, "undocumented path " + pattern);
                info.getMethodsCondition().getMethods().forEach(method -> {
                    operations.incrementAndGet();
                    assertTrue(
                            path.readOperationsMap()
                                    .containsKey(PathItem.HttpMethod.valueOf(
                                            method.name().toUpperCase(Locale.ROOT))),
                            "undocumented " + method + " " + pattern);
                });
            }
        });
        assertEquals(DOCUMENTED_OPERATIONS, operations.get(), "every story controller must be in the slice");
    }
}
