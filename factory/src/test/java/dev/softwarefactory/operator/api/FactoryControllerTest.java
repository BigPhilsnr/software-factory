package dev.softwarefactory.operator.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.softwarefactory.operator.security.OperatorToken;
import dev.softwarefactory.platform.FactorySettings;
import dev.softwarefactory.platform.InfrastructureException;
import dev.softwarefactory.platform.WorkflowConflictException;
import dev.softwarefactory.run.RunState;
import dev.softwarefactory.run.RunStore;
import dev.softwarefactory.validation.Preflight;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** The HTTP contract of the operator API: routes, payload shapes and the status of every failure. */
class FactoryControllerTest {
    private static final String ID = "00000000-0000-0000-0000-0000000000cc";
    private static final String ACTIONS = "/factory/api/runs/" + ID + "/actions";

    private final FactoryService factory = mock(FactoryService.class);
    private MockMvc api;

    @BeforeEach
    void setup() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        api = MockMvcBuilders.standaloneSetup(
                        new FactoryController(factory, new OperatorToken("page-token"), FactorySettings.from(Map.of())))
                .setValidator(validator)
                // The application serves the controller's Jackson 2 trees through the Jackson 2 converter.
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void configTellsThePageItsTokenAndWhatIsAvailable() throws Exception {
        when(factory.validatorStatus())
                .thenReturn(new Preflight(false, "Validator image missing", Instant.parse("2026-01-01T00:00:00Z")));
        api.perform(get("/factory/api/config"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token").value("page-token"))
                .andExpect(jsonPath("$.liveReady").value(false))
                .andExpect(
                        jsonPath("$.liveBlocker").value("Add ANTHROPIC_API_KEY to .env and restart for live features."))
                .andExpect(jsonPath("$.model").value(FactorySettings.DEFAULT_MODEL))
                .andExpect(jsonPath("$.validator.ready").value(false))
                .andExpect(jsonPath("$.validator.detail").value("Validator image missing"))
                .andExpect(jsonPath("$.validator.checkedAt").value("2026-01-01T00:00:00Z"));
    }

    @Test
    void runsDetailMetricsAndArtifactsAreServedAsJson() throws Exception {
        var state = new RunState(ID, "demo", "hash");
        when(factory.runs()).thenReturn(List.of(state));
        when(factory.detail(ID)).thenReturn(Map.of("requirement", "Add expiry"));
        when(factory.metrics()).thenReturn(Map.of("sample", "Latest 100 updated runs"));
        when(factory.artifact(ID, "plan-v1.txt")).thenReturn("The plan");
        api.perform(get("/factory/api/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ID))
                .andExpect(jsonPath("$[0].status").value("CREATED"));
        api.perform(get("/factory/api/runs/" + ID))
                .andExpect(jsonPath("$.requirement").value("Add expiry"));
        api.perform(get("/factory/api/metrics")).andExpect(jsonPath("$.sample").value("Latest 100 updated runs"));
        api.perform(get("/factory/api/runs/" + ID + "/artifacts/plan-v1.txt"))
                .andExpect(jsonPath("$.name").value("plan-v1.txt"))
                .andExpect(jsonPath("$.text").value("The plan"));
    }

    @Test
    void runsAreCreatedFromAFeatureRequestOrANamedScenario() throws Exception {
        var state = new RunState(ID, "demo", "hash");
        when(factory.feature("Add expiry")).thenReturn(state);
        when(factory.scenario("bugfix", "fixture")).thenReturn(state);
        when(factory.scenario("bugfix", "live")).thenReturn(state);
        api.perform(json(post("/factory/api/runs"), "{\"kind\":\"feature\",\"requirement\":\"Add expiry\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID));
        api.perform(json(post("/factory/api/runs"), "{\"kind\":\"scenario\",\"scenario\":\"bugfix\"}"))
                .andExpect(status().isOk());
        api.perform(json(
                        post("/factory/api/runs"), "{\"kind\":\"scenario\",\"scenario\":\"bugfix\",\"mode\":\"live\"}"))
                .andExpect(status().isOk());
        verify(factory).scenario("bugfix", "fixture");
        verify(factory).scenario("bugfix", "live");
    }

    @Test
    void everyActionIsRoutedToItsOperation() throws Exception {
        api.perform(json(post(ACTIONS), "{\"action\":\"advance\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"accepted\":true}"));
        api.perform(json(post(ACTIONS), "{\"action\":\"approve\",\"hash\":\"abc\"}"))
                .andExpect(status().isOk());
        api.perform(json(post(ACTIONS), "{\"action\":\"reject\",\"hash\":\"abc\"}"))
                .andExpect(status().isOk());
        api.perform(json(post(ACTIONS), "{\"action\":\"clarify\",\"answer\":\"One region\"}"))
                .andExpect(status().isOk());
        api.perform(json(post(ACTIONS), "{\"action\":\"revise\",\"task\":\"apply\",\"feedback\":\"Smaller\"}"))
                .andExpect(status().isOk());
        verify(factory).advance(ID);
        verify(factory).approve(ID, "abc");
        verify(factory).reject(ID, "abc");
        verify(factory).clarify(ID, "One region");
        verify(factory).revise(ID, "apply", "Smaller");
    }

    @Test
    void malformedRequestsAreRefusedWithoutReachingTheService() throws Exception {
        for (String body : List.of("{\"action\":\"delete\"}", "{}", "not json")) {
            api.perform(json(post(ACTIONS), body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"error\":\"Invalid request fields\"}"));
        }
        api.perform(json(post("/factory/api/runs"), "{\"kind\":\"scenario\",\"mode\":\"dry-run\"}"))
                .andExpect(status().isBadRequest());
        api.perform(post(ACTIONS).contentType(MediaType.TEXT_PLAIN).content("advance"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").exists());
        verify(factory, org.mockito.Mockito.never()).advance(any());
    }

    @Test
    void failuresMapToMeaningfulStatusesWithAJsonErrorBody() throws Exception {
        doThrow(new RunStore.MissingRunException("Run not found: " + ID))
                .when(factory)
                .detail(ID);
        api.perform(get("/factory/api/runs/" + ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":\"Run not found: " + ID + "\"}"));

        doThrow(new NotFoundException("Artifact not found: x.txt"))
                .when(factory)
                .artifact(ID, "x.txt");
        api.perform(get("/factory/api/runs/" + ID + "/artifacts/x.txt")).andExpect(status().isNotFound());

        doThrow(new IllegalArgumentException("Unknown scenario")).when(factory).scenario(any(), any());
        api.perform(json(post("/factory/api/runs"), "{\"kind\":\"scenario\",\"scenario\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown scenario"));

        doThrow(new WorkflowConflictException("This run is already active"))
                .when(factory)
                .advance(ID);
        api.perform(json(post(ACTIONS), "{\"action\":\"advance\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("This run is already active"));

        doThrow(new ServiceUnavailableException("Two runs are active"))
                .when(factory)
                .approve(any(), any());
        api.perform(json(post(ACTIONS), "{\"action\":\"approve\",\"hash\":\"abc\"}"))
                .andExpect(status().isServiceUnavailable());

        doThrow(new InfrastructureException("Control database unavailable\nforged line"))
                .when(factory)
                .metrics();
        api.perform(get("/factory/api/metrics"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Control database unavailable\nforged line"));

        doThrow(new IllegalStateException("internal detail that must not leak"))
                .when(factory)
                .runs();
        api.perform(get("/factory/api/runs"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error")
                        .value("Factory operation failed. Check the database and server logs; IllegalStateException"));
    }
}
