package dev.softwarefactory.validation;

import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.governance.PolicyViolationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Docker invocation: an offline, read-only, capability-less Maven container that sees the candidate
 * read-only, a trusted build definition, and a host-owned report directory.
 */
final class DockerSandbox {
    static final String CONTAINER_PREFIX = "factory-validator-";
    static final String OWNER_LABEL = "dev.softwarefactory.validator.pid";
    static final String REPORTS_MOUNT = "/reports";

    private static final Logger LOG = LoggerFactory.getLogger(DockerSandbox.class);
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DOCKER_TIMEOUT = Duration.ofSeconds(20);
    private static final String DOCKER = "docker";
    private static final String FORMAT = "--format";
    private static final String MOUNT = "--mount";
    private static final String BIND = "type=bind,source=";
    private static final String READ_ONLY = ",readonly";
    private static final String ENVIRONMENT = "--env";

    private final Path mavenCache;
    private final HostCommand host;
    private final ValidatorContainers containers;

    DockerSandbox(Path mavenCache, HostCommand host) {
        this.mavenCache = mavenCache.toAbsolutePath();
        this.host = host;
        this.containers = new ValidatorContainers(host);
    }

    Preflight check(Instant now) {
        if (!Files.isDirectory(mavenCache)) return new Preflight(false, "Maven cache missing: " + mavenCache, now);
        Path here = Path.of(".").toAbsolutePath();
        try {
            if (!succeeds(here, List.of(DOCKER, "info", FORMAT, "{{.ServerVersion}}"))) {
                return new Preflight(false, "Docker is not available: start Docker Desktop or Colima", now);
            }
            for (String image : List.of(SandboxValidator.IMAGE, ValidationDatabase.IMAGE)) {
                if (!succeeds(here, List.of(DOCKER, "image", "inspect", FORMAT, "{{.Id}}", image)))
                    return new Preflight(false, "Validator image missing; run: docker pull " + image, now);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Preflight(false, "Docker check interrupted", now);
        }
        return new Preflight(true, "Docker and validator image available", now);
    }

    private boolean succeeds(Path directory, List<String> command) throws InterruptedException {
        try {
            host.run(directory, command, DOCKER_TIMEOUT);
            return true;
        } catch (IOException unavailable) {
            LOG.debug("Preflight command {} failed: {}", command.get(1), unavailable.getMessage());
            return false;
        }
    }

    /** Runs Maven with the given goals; test reports appear in {@code reports} on the host. */
    void maven(Path candidate, Path reports, List<String> goals) throws IOException, InterruptedException {
        String database = goals.contains("-Pintegration") ? CONTAINER_PREFIX + "db-" + UUID.randomUUID() : null;
        try {
            if (database != null) new ValidationDatabase(host).start(candidate, database);
            maven(candidate, reports, goals, database);
        } finally {
            if (database != null) containers.removeContainer(candidate, database);
        }
    }

    private void maven(Path candidate, Path reports, List<String> goals, String database)
            throws IOException, InterruptedException {
        validateMount(candidate);
        validateMount(mavenCache);
        validateMount(reports);
        Path build = buildDirectory(candidate);
        Path trustedPom = Files.createTempFile(siblingDirectory(candidate), "factory-validator-pom-", ".xml");
        try (var input = DockerSandbox.class.getResourceAsStream("/validation/shortener-pom.xml")) {
            if (input == null) throw new IllegalStateException("Trusted validator definition missing");
            Files.copy(input, trustedPom, StandardCopyOption.REPLACE_EXISTING);
        }
        validateMount(trustedPom);
        String container = CONTAINER_PREFIX + UUID.randomUUID();
        List<String> command = new ArrayList<>(isolation(container, database));
        command.addAll(mounts(candidate, build, reports, trustedPom));
        command.addAll(List.of(
                "--workdir",
                "/trusted",
                SandboxValidator.IMAGE,
                "mvn",
                "-o",
                "-q",
                "-Dmaven.repo.local=/m2",
                "-f",
                "/trusted/pom.xml"));
        command.addAll(goals);
        try {
            host.run(candidate, command, RUN_TIMEOUT);
        } finally {
            containers.removeContainer(candidate, container);
            Files.deleteIfExists(trustedPom);
        }
    }

    /** A host directory beside the candidate, so Docker Desktop/Colima can mount files created in it. */
    static Path siblingDirectory(Path candidate) {
        Path parent = candidate.toAbsolutePath().getParent();
        if (parent == null) throw new IllegalArgumentException("Candidate cannot be a filesystem root");
        return parent;
    }

    private static Path buildDirectory(Path candidate) throws IOException {
        Path build = candidate.resolve(GitWorkspace.BUILD_OUTPUT);
        for (Path part = build; part != null && !part.equals(candidate); part = part.getParent()) {
            if (Files.isSymbolicLink(part))
                throw new PolicyViolationException("Build directory cannot traverse symlinks");
        }
        return Files.createDirectories(build);
    }

    private static List<String> isolation(String container, String database) {
        return List.of(
                DOCKER,
                "run",
                "--rm",
                "--name",
                container,
                "--label",
                OWNER_LABEL + "=" + ProcessHandle.current().pid(),
                "--network",
                database == null ? "none" : "container:" + database,
                "--cpus",
                "2",
                "--memory",
                "1g",
                "--pids-limit",
                "128",
                "--cap-drop",
                "ALL",
                "--security-opt",
                "no-new-privileges",
                "--read-only",
                "--user",
                currentUser(),
                "--tmpfs",
                "/tmp:rw,nosuid,size=128m",
                ENVIRONMENT,
                "HOME=/tmp",
                ENVIRONMENT,
                "MAVEN_CONFIG=/tmp/.m2",
                ENVIRONMENT,
                "SHORTENER_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/shortener",
                ENVIRONMENT,
                "SHORTENER_TEST_DB_USER=shortener",
                ENVIRONMENT,
                "SHORTENER_TEST_DB_PASSWORD=shortener");
    }

    private List<String> mounts(Path candidate, Path build, Path reports, Path trustedPom) {
        return List.of(
                MOUNT,
                BIND + candidate.toAbsolutePath() + ",target=/workspace" + READ_ONLY,
                MOUNT,
                BIND + build.toAbsolutePath() + ",target=/workspace/" + GitWorkspace.BUILD_OUTPUT,
                MOUNT,
                BIND + reports.toAbsolutePath() + ",target=" + REPORTS_MOUNT,
                MOUNT,
                BIND + trustedPom + ",target=/trusted/pom.xml" + READ_ONLY,
                MOUNT,
                BIND + mavenCache + ",target=/m2" + READ_ONLY);
    }

    int sweepOrphanedContainers(boolean includeOwn) {
        return containers.sweepOrphanedContainers(includeOwn);
    }

    static void validateMount(Path path) {
        String value = path.toAbsolutePath().toString();
        if (value.contains(",") || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Mount paths cannot contain commas or control characters");
        }
    }

    private static String currentUser() {
        var user = new com.sun.security.auth.module.UnixSystem();
        return user.getUid() + ":" + user.getGid();
    }
}
