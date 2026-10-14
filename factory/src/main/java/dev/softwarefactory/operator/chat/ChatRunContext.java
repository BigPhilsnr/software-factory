package dev.softwarefactory.operator.chat;

import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.operator.api.FactoryService;
import dev.softwarefactory.operator.api.NotFoundException;
import dev.softwarefactory.run.RunState;
import dev.softwarefactory.run.TaskStatus;
import java.io.IOException;
import java.util.List;

/** Fresh, bounded run evidence for conversation, including runs created in the operator UI. */
final class ChatRunContext {
    private static final int MAX_EXCERPT = 6000;
    private static final List<String> ARTIFACTS = List.of("documentation", "validate", "implementation", "tests");
    private final FactoryService factory;

    ChatRunContext(FactoryService factory) {
        this.factory = factory;
    }

    String read(String selected) throws IOException {
        List<RunState> runs =
                selected == null ? factory.runs().stream().limit(3).toList() : List.of(factory.state(selected));
        StringBuilder context = new StringBuilder(
                selected == null
                        ? "No run selected. Recent runs, newest first (not a complete feature inventory):\n"
                        : "Explicitly selected run:\n");
        for (RunState state : runs) append(context, state);
        return context.toString();
    }

    private void append(StringBuilder context, RunState state) {
        context.append("\nRUN ")
                .append(state.id)
                .append(" status=")
                .append(state.status)
                .append(" mode=")
                .append(state.mode)
                .append("\nTasks: ")
                .append(state.tasks)
                .append("\nCandidate: ")
                .append(state.candidatePath)
                .append("\nOperator page: /factory/?run=")
                .append(state.id)
                .append("\nCOMPLETED means the isolated candidate passed its workflow and release review. "
                        + "It does not mean merged into the current checkout or deployed.\n");
        try {
            var detail = factory.detail(state.id);
            context.append("Requirement: ")
                    .append(excerpt(String.valueOf(detail.get("requirement"))))
                    .append('\n');
            if (!Boolean.TRUE.equals(detail.get("auditValid"))) {
                context.append("Audit could not be verified; do not rely on this run's outputs.\n");
                return;
            }
            for (String task : ARTIFACTS) appendArtifact(context, state, task);
        } catch (IOException | NotFoundException unavailable) {
            context.append("Run evidence unavailable; do not infer that the feature does not exist.\n");
        }
    }

    private void appendArtifact(StringBuilder context, RunState state, String task) throws IOException {
        Integer version = state.artifactVersions.get(task);
        if (state.tasks.get(task) != TaskStatus.DONE || version == null) return;
        String name = task + "-v" + version + ".txt";
        String text = factory.artifact(state.id, name);
        if (!Hashes.same(Hashes.sha256(text), state.artifactHashes.get(task))) {
            context.append("Artifact integrity unavailable: ").append(name).append('\n');
            return;
        }
        context.append("\nEVIDENCE evidence/")
                .append(state.id)
                .append('/')
                .append(name)
                .append(" (bounded excerpt):\n")
                .append(excerpt(text))
                .append('\n');
    }

    private static String excerpt(String value) {
        return value.length() <= MAX_EXCERPT ? value : value.substring(0, MAX_EXCERPT) + "\n[excerpt truncated]";
    }
}
