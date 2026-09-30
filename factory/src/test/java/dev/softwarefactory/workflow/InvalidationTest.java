package dev.softwarefactory.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InvalidationTest {
    private TaskSpec task(String id, String... deps) {
        return new TaskSpec(id, Stage.PLANNING, List.of(deps), TaskKind.ARTIFACT, "planner", "prompt", null, List.of(), List.of(), false);
    }

    @Test
    void preservesUnrelatedBranch() {
        var tasks = List.of(task("root"), task("left", "root"), task("right", "root"), task("join", "left", "right"));
        assertEquals(Set.of("left", "join"), Invalidation.descendants("left", tasks));
    }
}
