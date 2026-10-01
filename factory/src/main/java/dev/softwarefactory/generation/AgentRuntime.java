package dev.softwarefactory.generation;

import java.io.IOException;

/** Produces one task's output. The caller decides what, if anything, happens with the text. */
@FunctionalInterface
public interface AgentRuntime {
    String generate(String role, String prompt) throws IOException;
}
