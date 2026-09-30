package com.example.factory.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Replays recorded artifacts; never described as a live AI run. */
public final class FixtureRuntime implements AgentRuntime {
    private final Path fixture;

    public FixtureRuntime(Path fixture) { this.fixture = fixture; }

    @Override
    public String generate(String role, String prompt) throws IOException {
        return Files.readString(fixture);
    }
}
