package com.example.factory.infra;

public interface AgentRuntime {
    String generate(String role, String prompt) throws Exception;
}
