package dev.softwarefactory.generation;

import dev.softwarefactory.generation.tools.ToolSession;
import dev.softwarefactory.platform.FactorySettings;
import java.nio.file.Path;

/** Creates the live runtime for one invocation, bound to the checkout it may read and the budget it draws on. */
@FunctionalInterface
public interface AgentRuntimes {
    /**
     * @param checkout the only directory the agent's repository tools can read
     * @param beforeRequest reserves budget before every provider request; throwing refuses the request
     * @param audit receives tool and budget events for the caller's audit record
     */
    AgentRuntime live(Path checkout, ToolSession.BeforeRequest beforeRequest, ToolSession.Audit audit);

    /** Claude through Google ADK, sharing the process-wide provider and web clients. */
    static AgentRuntimes claude(ModelClients clients, FactorySettings settings) {
        return (checkout, beforeRequest, audit) ->
                new AdkClaudeRuntime(clients, settings, checkout, beforeRequest, audit);
    }
}
