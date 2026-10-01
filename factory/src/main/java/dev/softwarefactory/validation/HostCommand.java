package dev.softwarefactory.validation;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Runs one host command to completion and returns its output; a non-zero exit is a failure. */
@FunctionalInterface
interface HostCommand {
    String run(Path directory, List<String> arguments, Duration timeout) throws IOException, InterruptedException;
}
