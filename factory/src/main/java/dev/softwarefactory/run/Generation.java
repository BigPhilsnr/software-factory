package dev.softwarefactory.run;

import java.nio.file.Path;

/** What a generation needs, captured under the state lock before any concurrent work starts. */
record Generation(String role, String prompt, String fixture, Path scenarioFolder, Path candidate) {}
