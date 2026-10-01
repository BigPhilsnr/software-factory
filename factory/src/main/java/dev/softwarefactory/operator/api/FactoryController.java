package dev.softwarefactory.operator.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.softwarefactory.operator.security.OperatorToken;
import dev.softwarefactory.platform.FactorySettings;
import dev.softwarefactory.platform.InfrastructureException;
import dev.softwarefactory.platform.Json;
import dev.softwarefactory.platform.LogText;
import dev.softwarefactory.platform.WorkflowConflictException;
import dev.softwarefactory.run.RunMode;
import dev.softwarefactory.run.RunState;
import dev.softwarefactory.run.RunStore;
import jakarta.servlet.ServletException;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The operator HTTP API. Every error is a JSON {@code {"error": ...}} body with a meaningful status. */
@RestController
@RequestMapping("/factory/api")
public final class FactoryController {
    private static final Logger LOG = LoggerFactory.getLogger(FactoryController.class);
    private final FactoryService factory;
    private final OperatorToken token;

    private final FactorySettings settings;

    public FactoryController(FactoryService factory, OperatorToken token, FactorySettings settings) {
        this.factory = factory;
        this.token = token;
        this.settings = settings;
    }

    @GetMapping("/config")
    public ResponseEntity<JsonNode> config() {
        var validator = factory.validatorStatus();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token.value());
        body.put("liveReady", settings.liveReady());
        body.put("liveBlocker", settings.liveBlocker());
        body.put("model", settings.model());
        body.put(
                "validator",
                Map.of(
                        "ready",
                        validator.ready(),
                        "detail",
                        validator.detail(),
                        "checkedAt",
                        validator.checkedAt().toString()));
        return json(body);
    }

    @GetMapping("/metrics")
    public ResponseEntity<JsonNode> metrics() throws IOException {
        return json(factory.metrics());
    }

    @GetMapping("/runs")
    public ResponseEntity<JsonNode> runs() throws IOException {
        return json(factory.runs());
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<JsonNode> run(@PathVariable("id") String id) throws IOException {
        return json(factory.detail(id));
    }

    @GetMapping("/runs/{id}/artifacts/{name}")
    public ResponseEntity<JsonNode> artifact(@PathVariable("id") String id, @PathVariable("name") String name)
            throws IOException {
        return json(Map.of("name", name, "text", factory.artifact(id, name)));
    }

    @PostMapping("/runs")
    public ResponseEntity<JsonNode> create(@Valid @RequestBody CreateRunRequest body)
            throws IOException, InterruptedException {
        RunState state = "feature".equals(body.kind())
                ? factory.feature(body.requirement())
                : factory.scenario(body.scenario(), body.mode() == null ? RunMode.FIXTURE : body.mode());
        return json(state);
    }

    @PostMapping("/runs/{id}/actions")
    public ResponseEntity<JsonNode> action(@PathVariable("id") String id, @Valid @RequestBody ActionRequest body)
            throws IOException, InterruptedException {
        switch (body.action()) {
            case "advance" -> factory.advance(id);
            case "approve" -> factory.approve(id, body.hash());
            case "reject" -> factory.reject(id, body.hash());
            case "clarify" -> factory.clarify(id, body.answer());
            case "revise" -> factory.revise(id, body.task(), body.feedback());
            default -> throw new IllegalArgumentException("Unknown action");
        }
        return json(Map.of("accepted", true));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<JsonNode> invalidRequest() {
        return error(HttpStatus.BAD_REQUEST, "Invalid request fields");
    }

    @ExceptionHandler({RunStore.MissingRunException.class, NotFoundException.class})
    public ResponseEntity<JsonNode> notFound(RuntimeException failure) {
        return error(HttpStatus.NOT_FOUND, failure.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<JsonNode> invalid(IllegalArgumentException failure) {
        return error(HttpStatus.BAD_REQUEST, String.valueOf(failure.getMessage()));
    }

    @ExceptionHandler(WorkflowConflictException.class)
    public ResponseEntity<JsonNode> conflict(WorkflowConflictException failure) {
        return error(HttpStatus.CONFLICT, failure.getMessage());
    }

    @ExceptionHandler({ServiceUnavailableException.class, InfrastructureException.class})
    public ResponseEntity<JsonNode> unavailable(Exception failure) {
        LOG.warn("Factory request deferred: {}", LogText.singleLine(failure.getMessage()));
        return error(HttpStatus.SERVICE_UNAVAILABLE, failure.getMessage());
    }

    /** Spring MVC protocol errors (unsupported media type, method, ...) keep their own status. */
    @ExceptionHandler(ServletException.class)
    public ResponseEntity<JsonNode> protocol(ServletException failure) {
        HttpStatusCode status =
                failure instanceof ErrorResponse response ? response.getStatusCode() : HttpStatus.BAD_REQUEST;
        return error(status, failure.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<JsonNode> failure(Exception failure) {
        LOG.error("Factory request failed", failure);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Factory operation failed. Check the database and server logs; "
                        + failure.getClass().getSimpleName());
    }

    private static ResponseEntity<JsonNode> error(HttpStatusCode status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Json.MAPPER.valueToTree(Map.of("error", String.valueOf(message))));
    }

    private static ResponseEntity<JsonNode> json(Object value) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(Json.MAPPER.valueToTree(value));
    }
}
