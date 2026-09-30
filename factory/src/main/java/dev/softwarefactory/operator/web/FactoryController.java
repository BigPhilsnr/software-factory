package dev.softwarefactory.operator.web;

import dev.softwarefactory.serialization.Json;
import dev.softwarefactory.workflow.RunState;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/factory/api")
public final class FactoryController {
    private final FactoryService factory;
    private final String token;
    public FactoryController(FactoryService factory, String token) { this.factory = factory; this.token = token; }

    @GetMapping("/config") public ResponseEntity<com.fasterxml.jackson.databind.JsonNode> config() throws Exception {
        return json(Map.of("token", token, "liveReady", System.getenv("ANTHROPIC_API_KEY") != null && !System.getenv("ANTHROPIC_API_KEY").isBlank(),
            "model", System.getenv().getOrDefault("CLAUDE_MODEL", "claude-sonnet-4-5")));
    }
    @GetMapping("/metrics") public ResponseEntity<com.fasterxml.jackson.databind.JsonNode> metrics() throws Exception { return json(factory.metrics()); }
    @GetMapping("/runs") public ResponseEntity<com.fasterxml.jackson.databind.JsonNode> runs() throws Exception { return json(factory.runs()); }
    @GetMapping("/runs/{id}") public ResponseEntity<com.fasterxml.jackson.databind.JsonNode> run(@PathVariable("id") String id) throws Exception { return json(factory.detail(id)); }
    @GetMapping("/runs/{id}/artifacts/{name}") public ResponseEntity<com.fasterxml.jackson.databind.JsonNode> artifact(@PathVariable("id") String id, @PathVariable("name") String name) throws Exception {
        return json(Map.of("name", name, "text", factory.artifact(id, name)));
    }
    @PostMapping("/runs") public ResponseEntity<com.fasterxml.jackson.databind.JsonNode> create(@RequestBody Map<String, String> body) throws Exception {
        RunState state = "feature".equals(body.get("kind")) ? factory.feature(body.get("requirement"))
            : factory.scenario(body.get("scenario"), body.getOrDefault("mode", "fixture"));
        return json(state);
    }
    @PostMapping("/runs/{id}/actions") public ResponseEntity<com.fasterxml.jackson.databind.JsonNode> action(@PathVariable("id") String id, @RequestBody Map<String, String> body) throws Exception {
        String action = body.getOrDefault("action", "");
        switch (action) {
            case "advance" -> factory.advance(id);
            case "approve" -> factory.approve(id, body.get("hash"));
            case "reject" -> factory.reject(id, body.get("hash"));
            case "clarify" -> factory.clarify(id, body.get("answer"));
            case "revise" -> factory.revise(id, body.get("task"), body.get("feedback"));
            default -> throw new IllegalArgumentException("Unknown action");
        }
        return json(Map.of("accepted", true));
    }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<com.fasterxml.jackson.databind.JsonNode> invalid(RuntimeException failure) throws Exception {
        return ResponseEntity.status(409).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(Json.MAPPER.valueToTree(Map.of("error", String.valueOf(failure.getMessage()))));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<com.fasterxml.jackson.databind.JsonNode> failure(Exception failure) throws Exception {
        return ResponseEntity.status(500).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(Json.MAPPER.valueToTree(Map.of("error", "Factory operation failed. Check the database and server logs; " + failure.getClass().getSimpleName())));
    }
    private ResponseEntity<com.fasterxml.jackson.databind.JsonNode> json(Object value) throws Exception {
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Json.MAPPER.valueToTree(value));
    }
}
