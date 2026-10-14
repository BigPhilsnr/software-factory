package dev.softwarefactory.candidate;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Independent Git snapshot: no worktree pointer, hooks, credentials or host build execution. */
final class DraftRepository {
    private static final Map<String, String> ENVIRONMENT = Map.of(
            "PATH",
            "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin",
            "GIT_CONFIG_NOSYSTEM",
            "1",
            "GIT_CONFIG_GLOBAL",
            "/dev/null",
            "GIT_TERMINAL_PROMPT",
            "0");

    private DraftRepository() {}

    static String create(Path source, Path target) throws IOException, InterruptedException {
        DraftFiles.requireNoLinks(source, source);
        DraftFiles.requireNoLinks(target.getParent(), target);
        Files.createDirectories(target);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                String name = java.util.Objects.requireNonNull(directory.getFileName())
                        .toString();
                if (!directory.equals(source) && (".git".equals(name) || "target".equals(name) || ".runs".equals(name)))
                    return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (".git"
                        .equals(java.util.Objects.requireNonNull(file.getFileName())
                                .toString())) return FileVisitResult.CONTINUE;
                DraftFiles.requireNoLinks(source, file);
                if (!attributes.isRegularFile()) throw new IOException("Candidate contains a non-regular file");
                Files.copy(file, target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
        git(target, "init", "-q");
        git(target, "add", "--all", "--force", "--", ".");
        git(
                target,
                "-c",
                "user.name=Factory Draft",
                "-c",
                "user.email=draft@factory.invalid",
                "-c",
                "commit.gpgsign=false",
                "commit",
                "--allow-empty",
                "-qm",
                "Draft input snapshot");
        return git(target, "rev-parse", "HEAD").strip();
    }

    static String git(Path directory, String... arguments) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false"));
        command.addAll(List.of(arguments));
        return CommandRunner.checked(CommandRunner.Invocation.of(directory, command, Duration.ofSeconds(60))
                .withIsolatedEnvironment(ENVIRONMENT));
    }
}
