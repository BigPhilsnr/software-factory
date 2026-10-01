package dev.softwarefactory.scenario;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Validates dependency and stage order before any task is dispatched, then answers which tasks may run. */
public final class TaskGraph {
    private static final Pattern TASK_ID = Pattern.compile("[a-z0-9-]{1,64}");

    private final List<TaskSpec> ordered;
    private final Map<String, TaskSpec> byId = new HashMap<>();

    public TaskGraph(List<TaskSpec> tasks) {
        if (tasks == null || tasks.isEmpty()) throw new IllegalArgumentException("Task graph is empty");
        this.ordered = List.copyOf(tasks);
        for (TaskSpec task : ordered) register(task);
        for (TaskSpec task : ordered) requireOrderedDependencies(task);
        requireAcyclic();
        for (TaskSpec task : ordered) {
            if (task.kind() == TaskKind.RELEASE) requireReleaseJoinsValidatedWork(task);
        }
    }

    /** Tasks in scenario order that are still pending and whose dependencies are all done. */
    public List<TaskSpec> ready(Predicate<String> pending, Predicate<String> done) {
        List<TaskSpec> result = new ArrayList<>();
        for (TaskSpec task : ordered) {
            if (pending.test(task.id()) && task.dependsOn().stream().allMatch(done)) result.add(task);
        }
        return result;
    }

    private void register(TaskSpec task) {
        if (!hasIdentity(task)) throw new IllegalArgumentException("Invalid task identity or stage");
        if (byId.putIfAbsent(task.id(), task) != null) {
            throw new IllegalArgumentException("Duplicate task ID: " + task.id());
        }
        requireGoverned(task);
    }

    /** A patch must declare where it may write; a release can never skip its approval. */
    private static void requireGoverned(TaskSpec task) {
        if (task.kind() == TaskKind.PATCH && task.writeScope().isEmpty()) {
            throw new IllegalArgumentException("Patch task has no write scope: " + task.id());
        }
        if (task.kind() == TaskKind.RELEASE && !task.requiresApproval()) {
            throw new IllegalArgumentException("Release requires approval: " + task.id());
        }
    }

    private static boolean hasIdentity(TaskSpec task) {
        return task.id() != null
                && TASK_ID.matcher(task.id()).matches()
                && task.stage() != null
                && task.kind() != null
                && task.role() != null
                && !task.role().isBlank();
    }

    private void requireOrderedDependencies(TaskSpec task) {
        for (String dependency : task.dependsOn()) {
            TaskSpec predecessor = byId.get(dependency);
            if (predecessor == null) throw new IllegalArgumentException("Unknown dependency: " + dependency);
            if (predecessor.stage().ordinal() > task.stage().ordinal()) {
                throw new IllegalArgumentException("Dependency reverses lifecycle stage: " + task.id());
            }
        }
    }

    private void requireAcyclic() {
        Set<String> complete = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (TaskSpec task : ordered) visit(task.id(), visiting, complete);
    }

    /** A release must sit downstream of everything, including a validation that covers every patch. */
    private void requireReleaseJoinsValidatedWork(TaskSpec release) {
        Set<String> upstream = ancestors(release.id());
        if (ordered.stream().anyMatch(task -> !task.id().equals(release.id()) && !upstream.contains(task.id()))) {
            throw new IllegalArgumentException("Release must join all required work: " + release.id());
        }
        List<TaskSpec> validations = ordered.stream()
                .filter(task -> task.kind() == TaskKind.VALIDATE && upstream.contains(task.id()))
                .toList();
        if (validations.isEmpty()) throw new IllegalArgumentException("Release requires passing validation");
        for (TaskSpec task : ordered) {
            if (task.kind() == TaskKind.PATCH
                    && validations.stream()
                            .noneMatch(validation -> ancestors(validation.id()).contains(task.id()))) {
                throw new IllegalArgumentException("Patch lacks downstream validation: " + task.id());
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
}
