package dev.softwarefactory.execution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Bounded process lifetime and diagnostics, with no interactive input. */
public final class CommandRunner {
    private static final int MAX_OUTPUT = 2 * 1024 * 1024;
    private CommandRunner() {}

    public record Result(int exitCode, String output) {}
    public static final class Failed extends IOException {
        private final int exitCode;
        Failed(int exitCode, String output) { super("Command exited " + exitCode + ":\n" + output); this.exitCode = exitCode; }
        public int exitCode() { return exitCode; }
    }

    public static Result run(Path directory, List<String> arguments, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(arguments).directory(directory.toFile())
            .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
            .redirectErrorStream(true).start();
        FutureTask<String> reader = new FutureTask<>(() -> {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            boolean overflow = false;
            try (var input = process.getInputStream()) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    int accepted = Math.min(count, MAX_OUTPUT - output.size());
                    output.write(buffer, 0, accepted);
                    overflow |= accepted != count;
                }
            }
            if (overflow) throw new IOException("Command output exceeded 2 MiB");
            return output.toString(StandardCharsets.UTF_8);
        });
        Thread.startVirtualThread(reader);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IOException("Command timed out: " + arguments.getFirst());
            return new Result(process.exitValue(), reader.get(2, TimeUnit.SECONDS));
        } finally {
            process.descendants().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close();
            reader.cancel(true);
        }
    }

    public static String checked(Path directory, List<String> arguments, Duration timeout) throws Exception {
        Result result = run(directory, arguments, timeout);
        if (result.exitCode() != 0) throw new Failed(result.exitCode(), result.output());
        return result.output();
    }
}
