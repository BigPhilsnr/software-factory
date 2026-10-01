package dev.softwarefactory.scenario;

import java.util.List;

public record ScenarioSpec(String id, String requirement, String baselineTag, List<TaskSpec> tasks) {
    public ScenarioSpec {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
}
