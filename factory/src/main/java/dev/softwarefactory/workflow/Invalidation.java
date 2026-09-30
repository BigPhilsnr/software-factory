package dev.softwarefactory.workflow;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Invalidation {
    private Invalidation() {}

    public static Set<String> descendants(String changed, List<TaskSpec> tasks) {
        Set<String> affected = new HashSet<>();
        affected.add(changed);
        boolean growing;
        do {
            growing = false;
            for (TaskSpec task : tasks) {
                if (task.dependsOn().stream().anyMatch(affected::contains)) growing |= affected.add(task.id());
            }
        } while (growing);
        return affected;
    }
}
