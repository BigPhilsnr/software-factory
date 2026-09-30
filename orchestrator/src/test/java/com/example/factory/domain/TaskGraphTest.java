package com.example.factory.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskGraphTest {
    private TaskSpec task(String id, String... deps) {
        return new TaskSpec(id, Stage.PLANNING, List.of(deps), TaskKind.ARTIFACT, "planner", "prompt", null, List.of(), List.of(), false);
    }

    @Test
    void requiresJoinBeforeDownstreamTask() {
        TaskSpec root = task("root");
        TaskSpec left = task("left", "root");
        TaskSpec right = task("right", "root");
        TaskSpec join = task("join", "left", "right");
        TaskGraph graph = new TaskGraph(List.of(root, left, right, join));
        assertEquals(List.of(left, right), graph.ready(List.of(root, left, right, join), Map.of("root", TaskStatus.DONE)));
        assertEquals(List.of(join), graph.ready(List.of(root, left, right, join),
            Map.of("root", TaskStatus.DONE, "left", TaskStatus.DONE, "right", TaskStatus.DONE)));
    }

    @Test
    void rejectsCyclesAndUnknownDependencies() {
        assertThrows(IllegalArgumentException.class, () -> new TaskGraph(List.of(task("a", "b"), task("b", "a"))));
        assertThrows(IllegalArgumentException.class, () -> new TaskGraph(List.of(task("a", "missing"))));
    }

    @Test
    void rejectsEmptyGraphsAndUnboundedPatches() {
        assertThrows(IllegalArgumentException.class, () -> new TaskGraph(List.of()));
        TaskSpec unbounded = new TaskSpec("patch", Stage.IMPLEMENTATION, List.of(), TaskKind.PATCH,
            "implementer", "patch", null, List.of(), List.of(), false);
        assertThrows(IllegalArgumentException.class, () -> new TaskGraph(List.of(unbounded)));
    }
}
