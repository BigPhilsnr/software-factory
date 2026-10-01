package dev.softwarefactory.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates dependency and stage order before any task is dispatched. */
public final class TaskGraph {
    private final Map<String, TaskSpec> byId = new HashMap<>();

    public TaskGraph(List<TaskSpec> tasks) {
        if (tasks == null || tasks.isEmpty()) throw new IllegalArgumentException("Task graph is empty");
        for (TaskSpec task : tasks) {
            if (task.id() == null
                    || !task.id().matches("[a-z0-9-]{1,64}")
                    || task.stage() == null
                    || task.kind() == null
                    || task.role() == null
                    || task.role().isBlank()) {
                throw new IllegalArgumentException("Invalid task identity or stage");
            }
            if (byId.putIfAbsent(task.id(), task) != null) {
                throw new IllegalArgumentException("Duplicate task ID: " + task.id());
            }
            if (task.kind() == TaskKind.PATCH && task.writeScope().isEmpty()) {
                throw new IllegalArgumentException("Patch task has no write scope: " + task.id());
            }
            if (task.kind() == TaskKind.RELEASE && !task.requiresApproval()) {
                throw new IllegalArgumentException("Release requires approval: " + task.id());
            }
        }
        for (TaskSpec task : tasks) {
            for (String dependency : task.dependsOn()) {
                TaskSpec predecessor = byId.get(dependency);
                if (predecessor == null) throw new IllegalArgumentException("Unknown dependency: " + dependency);
                if (predecessor.stage().ordinal() > task.stage().ordinal()) {
                    throw new IllegalArgumentException("Dependency reverses lifecycle stage: " + task.id());
                }
            }
        }
        Set<String> complete = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (TaskSpec task : tasks) visit(task.id(), visiting, complete);
        for (TaskSpec release :
                tasks.stream().filter(t -> t.kind() == TaskKind.RELEASE).toList()) {
            Set<String> upstream = ancestors(release.id());
            if (tasks.stream().anyMatch(t -> !t.id().equals(release.id()) && !upstream.contains(t.id()))) {
                throw new IllegalArgumentException("Release must join all required work: " + release.id());
            }
            List<TaskSpec> validations = tasks.stream()
                    .filter(t -> t.kind() == TaskKind.VALIDATE && upstream.contains(t.id()))
                    .toList();
            if (validations.isEmpty()) throw new IllegalArgumentException("Release requires passing validation");
            for (TaskSpec patch :
                    tasks.stream().filter(t -> t.kind() == TaskKind.PATCH).toList()) {
                if (validations.stream().noneMatch(t -> ancestors(t.id()).contains(patch.id()))) {
                    throw new IllegalArgumentException("Patch lacks downstream validation: " + patch.id());
                }
            }
        }
    }

    private Set<String> ancestors(String id) {
        Set<String> result = new HashSet<>();
        for (String dependency : byId.get(id).dependsOn()) {
            result.add(dependency);
            result.addAll(ancestors(dependency));
        }
        return result;
    }

    private void visit(String id, Set<String> visiting, Set<String> complete) {
        if (complete.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("Cycle at " + id);
        for (String dependency : byId.get(id).dependsOn()) visit(dependency, visiting, complete);
        visiting.remove(id);
        complete.add(id);
    }

    public List<TaskSpec> ready(List<TaskSpec> ordered, Map<String, TaskStatus> states) {
        List<TaskSpec> result = new ArrayList<>();
        for (TaskSpec task : ordered) {
            if (states.getOrDefault(task.id(), TaskStatus.PENDING) == TaskStatus.PENDING
                    && task.dependsOn().stream().allMatch(id -> states.get(id) == TaskStatus.DONE)) {
                result.add(task);
            }
        }
        return result;
    }
}
