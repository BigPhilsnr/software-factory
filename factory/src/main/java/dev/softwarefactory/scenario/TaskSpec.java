package dev.softwarefactory.scenario;

import java.util.List;

/** An immutable, versionable unit of engineering work. */
public record TaskSpec(
        String id,
        Stage stage,
        List<String> dependsOn,
        TaskKind kind,
        String role,
        String prompt,
        String fixture,
        List<String> acceptanceCriteria,
        List<String> writeScope,
        boolean requiresApproval) {
    public TaskSpec {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        writeScope = writeScope == null ? List.of() : List.copyOf(writeScope);
    }
}
