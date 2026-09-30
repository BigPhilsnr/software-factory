package dev.softwarefactory.agents;

public interface AgentRuntime {
    String generate(String role, String prompt) throws Exception;
}
