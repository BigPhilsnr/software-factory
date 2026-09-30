package com.example.factory.api;

import com.example.factory.domain.RunEngine;
import com.example.factory.domain.RunState;
import com.example.factory.domain.ScenarioSpec;
import com.example.factory.infra.ControlRepository;
import com.example.factory.infra.Json;
import java.nio.file.Path;
import java.nio.file.Files;

/** Operator CLI. Runs require a separate control database inaccessible to workers. */
public final class FactoryCli {
    private FactoryCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            usage();
            System.exit(2);
        }
        var repo = new ControlRepository(
            System.getenv().getOrDefault("CONTROL_DB_URL", "jdbc:postgresql://localhost:5434/control"),
            System.getenv().getOrDefault("CONTROL_DB_USER", "control"),
            System.getenv().getOrDefault("CONTROL_DB_PASSWORD", "control"));
        repo.initialize();
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (root.getFileName().toString().equals("orchestrator")) root = root.getParent();
        RunEngine engine = new RunEngine(repo, root);
        RunState result;
        switch (args[0]) {
            case "start" -> {
                if (args.length != 3) { usage(); return; }
                result = engine.start(Path.of(args[1]), args[2]);
            }
            case "advance" -> {
                if (args.length != 2) { usage(); return; }
                result = engine.advance(args[1]);
            }
            case "approve" -> {
                if (args.length != 3) { usage(); return; }
                result = engine.approve(args[1], args[2], true);
            }
            case "reject" -> {
                if (args.length != 2) { usage(); return; }
                result = engine.approve(args[1], null, false);
            }
            case "clarify" -> {
                if (args.length < 3) { usage(); return; }
                result = engine.clarify(args[1], String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
            }
            case "revise" -> {
                if (args.length != 3) { usage(); return; }
                result = engine.revise(args[1], args[2]);
            }
            case "status" -> {
                if (args.length != 2) { usage(); return; }
                result = repo.load(args[1]);
            }
            case "review" -> {
                if (args.length != 2) { usage(); return; }
                RunState state = repo.load(args[1]);
                if (state.pendingApprovalTask == null) throw new IllegalStateException("No pending approval");
                ScenarioSpec spec = Json.MAPPER.readValue(Files.readString(Path.of(state.specPath)), ScenarioSpec.class);
                var task = spec.tasks().stream().filter(item -> item.id().equals(state.pendingApprovalTask)).findFirst().orElseThrow();
                Path proposal = root.resolve("evidence").resolve(state.id).resolve(task.id() + "-v" + state.artifactVersions.get(task.id()) + ".txt");
                System.out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(java.util.Map.of(
                    "runId", state.id,
                    "task", task.id(),
                    "reviewedHash", state.pendingApprovalHash,
                    "baselineCommit", state.baselineCommit == null ? state.baselineTag : state.baselineCommit,
                    "writeScope", task.writeScope(),
                    "proposal", Files.exists(proposal) ? proposal.toString() : "none",
                    "validatedCandidateHash", state.validatedCandidateHash == null ? "not yet validated" : state.validatedCandidateHash)));
                return;
            }
            case "verify-audit" -> {
                if (args.length != 2) { usage(); return; }
                System.out.println(repo.auditValid(args[1]) ? "AUDIT_VALID" : "AUDIT_INVALID");
                return;
            }
            default -> { usage(); return; }
        }
        System.out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private static void usage() {
        System.err.println("Usage: start <scenario.json> <fixture|live> | advance <run-id> | review <run-id> | approve <run-id> <reviewed-hash> | reject <run-id> | clarify <run-id> <answer> | revise <run-id> <task-id> | status <run-id> | verify-audit <run-id>");
    }
}
