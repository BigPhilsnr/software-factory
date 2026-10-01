package dev.softwarefactory.execution;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CommandRunnerTest {
    @Test void closesInputAndDecodesUtf8() throws Exception {
        assertEquals("", CommandRunner.checked(Path.of("."), List.of("/bin/cat"), Duration.ofSeconds(2)));
        assertEquals("café", CommandRunner.checked(Path.of("."), List.of("/usr/bin/printf", "café"), Duration.ofSeconds(2)));
    }
    @Test void distinguishesExitFailureFromTimeoutAndCapsOutput() {
        assertThrows(CommandRunner.Failed.class, () -> CommandRunner.checked(Path.of("."), List.of("/bin/sh", "-c", "exit 7"), Duration.ofSeconds(2)));
        var timeout = assertThrows(java.io.IOException.class, () -> CommandRunner.checked(Path.of("."), List.of("/bin/sh", "-c", "sleep 30 & wait"), Duration.ofMillis(150)));
        assertFalse(timeout instanceof CommandRunner.Failed);
        assertThrows(Exception.class, () -> CommandRunner.checked(Path.of("."), List.of("python3", "-c", "print('x'*2200000)"), Duration.ofSeconds(3)));
    }
}
