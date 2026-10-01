package dev.softwarefactory.candidate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded process lifetime and diagnostics, with no interactive input. */
public final class CommandRunner {
    /** Diagnostics and status commands. */
    public static final int DEFAULT_OUTPUT_LIMIT = 2 * 1024 * 1024;
    /** Data-producing commands such as a full candidate diff. */
    public static final int DATA_OUTPUT_LIMIT = 64 * 1024 * 1024;

    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_UTF8_LENGTH = 4;
    /** Descendants are re-parented once their parent dies, so the tree is captured while it is alive. */
    private static final Duration PROCESS_TREE_POLL = Duration.ofMillis(100);

    private static final Duration OUTPUT_DRAIN = Duration.ofSeconds(2);
    private static final File NO_INPUT = new File("/dev/null");

    private CommandRunner() {}

    /** What happens when output exceeds the invocation limit. The process is stopped in both cases. */
    public enum Overflow {
        FAIL,
        TRUNCATE
    }

    public record Invocation(
            Path directory,
            List<String> arguments,
            Duration timeout,
            int outputLimit,
            Overflow overflow,
            Map<String, String> environment,
            boolean isolatedEnvironment) {
        public Invocation {
            arguments = List.copyOf(arguments);
            environment = Map.copyOf(environment);
            if (arguments.isEmpty()) throw new IllegalArgumentException("Command is empty");
            if (outputLimit < 1) throw new IllegalArgumentException("Output limit must be positive");
        }

        public static Invocation of(Path directory, List<String> arguments, Duration timeout) {
            return new Invocation(directory, arguments, timeout, DEFAULT_OUTPUT_LIMIT, Overflow.FAIL, Map.of(), false);
        }

        public Invocation withOutputLimit(int limit, Overflow policy) {
            return new Invocation(directory, arguments, timeout, limit, policy, environment, isolatedEnvironment);
        }

        /** Adds variables to the inherited environment. */
        public Invocation withEnvironment(Map<String, String> variables) {
            return new Invocation(directory, arguments, timeout, outputLimit, overflow, variables, isolatedEnvironment);
        }

        /** Replaces the inherited environment with exactly these variables. */
        public Invocation withIsolatedEnvironment(Map<String, String> variables) {
            return new Invocation(directory, arguments, timeout, outputLimit, overflow, variables, true);
        }
    }

    public record Result(int exitCode, String output, boolean truncated) {}

    public static final class Failed extends IOException {
        private final int exitCode;
        private final String output;

        public Failed(int exitCode, String output) {
            super("Command exited " + exitCode + ":\n" + output);
            this.exitCode = exitCode;
            this.output = output;
        }

        public int exitCode() {
            return exitCode;
        }

        public String output() {
            return output;
        }
    }

    public static final class TimedOut extends IOException {
        TimedOut(String command, Duration timeout) {
            super("Command timed out after " + timeout.toSeconds() + "s: " + command);
        }
    }

    public static final class OutputLimitExceeded extends IOException {
        OutputLimitExceeded(String command, int limit) {
            super("Command output exceeded " + limit + " bytes: " + command);
        }
    }

    private record Captured(byte[] bytes, boolean overflowed) {}

    public static Result run(Path directory, List<String> arguments, Duration timeout)
            throws IOException, InterruptedException {
        return run(Invocation.of(directory, arguments, timeout));
    }

    public static String checked(Path directory, List<String> arguments, Duration timeout)
            throws IOException, InterruptedException {
        return checked(Invocation.of(directory, arguments, timeout));
    }

    public static String checked(Invocation invocation) throws IOException, InterruptedException {
        Result result = run(invocation);
        if (result.exitCode() != 0) throw new Failed(result.exitCode(), result.output());
        return result.output();
    }

    @SuppressFBWarnings(
            value = "COMMAND_INJECTION",
            justification = "Running a caller-built argument vector is this class's purpose. There is no shell, so "
                    + "arguments are never re-parsed; callers pass fixed programs (git, docker) and validated values.")
    public static Result run(Invocation invocation) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(invocation.arguments())
                .directory(invocation.directory().toFile())
                .redirectInput(ProcessBuilder.Redirect.from(NO_INPUT))
                .redirectErrorStream(true);
        if (invocation.isolatedEnvironment()) builder.environment().clear();
        builder.environment().putAll(invocation.environment());
        Process process = builder.start();
        Set<ProcessHandle> tree = ConcurrentHashMap.newKeySet();
        FutureTask<Captured> reader = new FutureTask<>(() -> capture(process, tree, invocation.outputLimit()));
        Thread.startVirtualThread(reader);
        // Closing the output stream last releases a reader blocked on a pipe a descendant still holds open.
        try (InputStream ignoredOutput = process.getInputStream()) {
            return await(process, tree, reader, invocation);
        } finally {
            reader.cancel(true);
        }
    }

    private static Result await(
            Process process, Set<ProcessHandle> tree, FutureTask<Captured> reader, Invocation invocation)
            throws IOException, InterruptedException {
        String command = invocation.arguments().getFirst();
        try {
            long deadline = System.nanoTime() + invocation.timeout().toNanos();
            while (!process.waitFor(PROCESS_TREE_POLL.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(tree::add);
                if (System.nanoTime() - deadline >= 0) throw new TimedOut(command, invocation.timeout());
            }
            return result(process.exitValue(), reader.get(OUTPUT_DRAIN.toMillis(), TimeUnit.MILLISECONDS), invocation);
        } catch (ExecutionException failure) {
            throw new IOException("Could not read command output: " + command, failure);
        } catch (TimeoutException stillOpen) {
            // A surviving descendant holds the output pipe open after the parent exited.
            throw new IOException("Command output did not close: " + command, stillOpen);
        } finally {
            destroyTree(process, tree);
        }
    }

    private static Result result(int exitCode, Captured captured, Invocation invocation) throws OutputLimitExceeded {
        if (!captured.overflowed()) {
            return new Result(exitCode, new String(captured.bytes(), StandardCharsets.UTF_8), false);
        }
        if (invocation.overflow() == Overflow.FAIL) {
            throw new OutputLimitExceeded(invocation.arguments().getFirst(), invocation.outputLimit());
        }
        return new Result(exitCode, decodeUtf8Prefix(captured.bytes()), true);
    }

    private static Captured capture(Process process, Set<ProcessHandle> tree, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = process.getInputStream()) {
            for (int count = input.read(buffer); count != -1; count = input.read(buffer)) {
                int accepted = Math.min(count, limit - output.size());
                output.write(buffer, 0, accepted);
                if (accepted != count) {
                    // Stop a runaway producer now rather than draining it until the timeout.
                    destroyTree(process, tree);
                    return new Captured(output.toByteArray(), true);
                }
            }
        }
        return new Captured(output.toByteArray(), false);
    }

    private static void destroyTree(Process process, Set<ProcessHandle> captured) {
        process.descendants().forEach(captured::add);
        captured.forEach(child -> {
            if (child.isAlive()) child.destroyForcibly();
        });
        if (process.isAlive()) process.destroyForcibly();
    }

    /** Decodes bytes cut at an arbitrary limit without emitting a replacement for a split final character. */
    static String decodeUtf8Prefix(byte[] bytes) {
        int end = bytes.length;
        int lead = end - 1;
        // Step back over continuation bytes (10xxxxxx) to the lead byte of the final character.
        while (lead >= Math.max(0, end - MAX_UTF8_LENGTH) && (bytes[lead] & 0xC0) == 0x80) lead--;
        if (lead >= Math.max(0, end - MAX_UTF8_LENGTH) && end - lead < encodedLength(bytes[lead] & 0xFF)) end = lead;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static int encodedLength(int leadByte) {
        if (leadByte >= 0xF0) return 4;
        if (leadByte >= 0xE0) return 3;
        return leadByte >= 0xC0 ? 2 : 1;
    }
}
