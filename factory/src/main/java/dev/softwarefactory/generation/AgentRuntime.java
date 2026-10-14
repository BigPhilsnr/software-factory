package dev.softwarefactory.generation;

import dev.softwarefactory.candidate.DraftWorkspace;
import java.io.IOException;

/** Produces task artifacts or scoped draft edits. The control plane owns candidate application and approval. */
@FunctionalInterface
public interface AgentRuntime {
    String generate(String role, String prompt) throws IOException;
    /** Scripted runtimes may supply a diff; the live ADK runtime overrides this with scoped editing tools. */
    default void draft(String role, String prompt, DraftWorkspace workspace) throws IOException, InterruptedException {
        workspace.applyPatch(generate(role, prompt));
    }
}
