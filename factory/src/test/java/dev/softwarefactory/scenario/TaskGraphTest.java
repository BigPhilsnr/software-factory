package dev.softwarefactory.scenario;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskGraphTest {
    @Test
    void releaseCannotBypassValidationOrLeaveUnjoinedWork() {
        TaskSpec patch = new TaskSpec(
                "patch",
                Stage.IMPLEMENTATION,
                List.of(),
                TaskKind.PATCH,
                "implementer",
                "patch",
                null,
                List.of(),
                List.of("shortener"),
                false);
        TaskSpec release = new TaskSpec(
                "release",
                Stage.RELEASE,
                List.of("patch"),
                TaskKind.RELEASE,
                "release",
                "review",
                null,
                List.of(),
                List.of(),
                true);
        assertThrows(IllegalArgumentException.class, () -> new TaskGraph(List.of(patch, release)));
        TaskSpec validation = new TaskSpec(
                "validate",
                Stage.VALIDATION,
                List.of(),
                TaskKind.VALIDATE,
                "validator",
                "test",
                null,
                List.of(),
                List.of(),
                false);
        TaskSpec earlyRelease = new TaskSpec(
                "release",
                Stage.RELEASE,
                List.of("validate"),
                TaskKind.RELEASE,
                "release",
                "review",
                null,
                List.of(),
                List.of(),
                true);
        assertThrows(IllegalArgumentException.class, () -> new TaskGraph(List.of(patch, validation, earlyRelease)));
    }

    private static List<TaskSpec> ready(TaskGraph graph, Set<String> done) {
        return graph.ready(id -> !done.contains(id), done::contains);
    }

    private TaskSpec task(String id, String... deps) {
        return new TaskSpec(
                id,
                Stage.PLANNING,
                List.of(deps),
                TaskKind.ARTIFACT,
                "planner",
                "prompt",
                null,
                List.of(),
                List.of(),
                false);
    }

    @Test
    void requiresJoinBeforeDownstreamTask() {
        TaskSpec root = task("root");
        TaskSpec left = task("left", "root");
        TaskSpec right = task("right", "root");
        TaskSpec join = task("join", "left", "right");
        TaskGraph graph = new TaskGraph(List.of(root, left, right, join));
        assertEquals(List.of(left, right), ready(graph, Set.of("root")));
        assertEquals(List.of(join), ready(graph, Set.of("root", "left", "right")));
    }

    @Test
    void rejectsCyclesAndUnknownDependencies() {
        assertThrows(IllegalArgumentException.class, () -> new TaskGraph(List.of(task("a", "b"), task("b", "a"))));
        assertThrows(IllegalArgumentException.class, () -> new TaskGraph(List.of(task("a", "missing"))));
    }

    @Test
    void rejectsEmptyGraphsAndUnboundedPatches() {
        assertThrows(IllegalArgumentException.class, () -> new TaskGraph(List.of()));
        TaskSpec unbounded = new TaskSpec(
                "patch",
                Stage.IMPLEMENTATION,
                List.of(),
                TaskKind.PATCH,
                "implementer",
                "patch",
                null,
                List.of(),
                List.of(),
                false);
        assertThrows(IllegalArgumentException.class, () -> new TaskGraph(List.of(unbounded)));
    }
}
