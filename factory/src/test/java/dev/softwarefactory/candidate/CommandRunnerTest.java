package dev.softwarefactory.candidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandRunnerTest {
    private static final Path HERE = Path.of(".");

    @TempDir
    Path temporary;

    @Test
    void closesInputAndDecodesUtf8() throws Exception {
        assertEquals("", CommandRunner.checked(HERE, List.of("/bin/cat"), Duration.ofSeconds(5)));
        assertEquals("café", CommandRunner.checked(HERE, List.of("/usr/bin/printf", "café"), Duration.ofSeconds(5)));
    }

    @Test
    void distinguishesExitFailureFromTimeout() {
        var failed = assertThrows(
                CommandRunner.Failed.class,
                () -> CommandRunner.checked(
                        HERE, List.of("/bin/sh", "-c", "echo diagnostic; exit 7"), Duration.ofSeconds(5)));
        assertEquals(7, failed.exitCode());
        assertTrue(failed.output().contains("diagnostic"));
        assertThrows(
                CommandRunner.TimedOut.class,
                () -> CommandRunner.checked(HERE, List.of("/bin/sh", "-c", "sleep 30"), Duration.ofMillis(300)));
    }

    @Test
    void defaultLimitFailsButDataCommandsCanProduceLargeOutput() throws Exception {
        List<String> large = List.of("/bin/sh", "-c", "head -c 3000000 /dev/zero | tr '\\0' 'x'");
        assertThrows(
                CommandRunner.OutputLimitExceeded.class,
                () -> CommandRunner.checked(HERE, large, Duration.ofSeconds(20)));
        String output = CommandRunner.checked(CommandRunner.Invocation.of(HERE, large, Duration.ofSeconds(20))
                .withOutputLimit(CommandRunner.DATA_OUTPUT_LIMIT, CommandRunner.Overflow.FAIL));
        assertEquals(3_000_000, output.length());
    }

    @Test
    void truncationStopsTheProducerAndReportsIt() throws Exception {
        var result = CommandRunner.run(
                CommandRunner.Invocation.of(HERE, List.of("/bin/sh", "-c", "yes"), Duration.ofSeconds(20))
                        .withOutputLimit(1000, CommandRunner.Overflow.TRUNCATE));
        assertTrue(result.truncated());
        assertEquals(1000, result.output().length());
    }

    @Test
    void timeoutKillsDescendantsStartedByTheCommand() throws Exception {
        Path pidFile = temporary.resolve("child.pid");
        String script = "sleep 60 & echo $! > '" + pidFile + "'; wait";
        assertThrows(
                CommandRunner.TimedOut.class,
                () -> CommandRunner.checked(HERE, List.of("/bin/sh", "-c", script), Duration.ofSeconds(2)));
        long child = Long.parseLong(Files.readString(pidFile).strip());
        var handle = ProcessHandle.of(child);
        if (handle.isPresent()) handle.get().onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(
                ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false),
                "Background child must not outlive the timeout");
    }

    @Test
    void isolatedEnvironmentDoesNotInheritVariables() throws Exception {
        String output =
                CommandRunner.checked(CommandRunner.Invocation.of(HERE, List.of("/usr/bin/env"), Duration.ofSeconds(5))
                        .withIsolatedEnvironment(Map.of("ONLY", "value")));
        assertEquals("ONLY=value", output.strip());
    }

    @Test
    void truncatedUtf8NeverEndsWithAReplacementCharacter() {
        byte[] encoded = "aé€😀".getBytes(StandardCharsets.UTF_8);
        for (int length = 0; length <= encoded.length; length++) {
            String decoded = CommandRunner.decodeUtf8Prefix(Arrays.copyOf(encoded, length));
            assertFalse(decoded.contains("�"), "length " + length);
            assertTrue("aé€😀".startsWith(decoded));
        }
    }
}
