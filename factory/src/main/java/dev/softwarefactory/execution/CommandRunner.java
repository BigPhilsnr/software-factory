package dev.softwarefactory.execution;

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

        Failed(int exitCode, String output) {
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
        String command = invocation.arguments().getFirst();
        try {
            long deadline = System.nanoTime() + invocation.timeout().toNanos();
            while (!process.waitFor(PROCESS_TREE_POLL.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(tree::add);
                if (System.nanoTime() - deadline >= 0) throw new TimedOut(command, invocation.timeout());
            }
            Captured captured = reader.get(OUTPUT_DRAIN.toMillis(), TimeUnit.MILLISECONDS);
            if (captured.overflowed() && invocation.overflow() == Overflow.FAIL) {
                throw new OutputLimitExceeded(command, invocation.outputLimit());
            }
            String output = captured.overflowed()
                    ? decodeUtf8Prefix(captured.bytes())
                    : new String(captured.bytes(), StandardCharsets.UTF_8);
            return new Result(process.exitValue(), output, captured.overflowed());
        } catch (ExecutionException failure) {
            throw new IOException("Could not read command output: " + command, failure.getCause());
        } catch (TimeoutException stillOpen) {
            // A surviving descendant holds the output pipe open after the parent exited.
            throw new IOException("Command output did not close: " + command, stillOpen);
        } finally {
            destroyTree(process, tree);
            process.getInputStream().close();
            reader.cancel(true);
        }
    }

    private static Captured capture(Process process, Set<ProcessHandle> tree, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = process.getInputStream()) {
            int count;
            while ((count = input.read(buffer)) != -1) {
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
        for (int index = end - 1; index >= Math.max(0, end - 4); index--) {
            int value = bytes[index] & 0xFF;
            if ((value & 0xC0) == 0x80) continue;
            int length = value >= 0xF0 ? 4 : value >= 0xE0 ? 3 : value >= 0xC0 ? 2 : 1;
            if (end - index < length) end = index;
            break;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
