package dev.softwarefactory.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/** Executes candidate build logic without governance credentials or network access. */
public final class SandboxValidator {
    /** Pinned by digest: a tag could be re-pointed at different build tooling. */
    public static final String IMAGE = "maven@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320";

    static final String CONTAINER_PREFIX = "factory-validator-";
    static final String OWNER_LABEL = "dev.softwarefactory.validator.pid";
    static final String REPORTS_MOUNT = "/reports";
    private static final Logger LOG = LoggerFactory.getLogger(SandboxValidator.class);
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DOCKER_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PREFLIGHT_TTL = Duration.ofSeconds(30);
    /** Container and host clocks can disagree slightly about report modification times. */
    private static final Duration REPORT_CLOCK_TOLERANCE = Duration.ofSeconds(2);

    private static final int DOCKER_FAILURE_EXIT = 125;
    private static final int MAVEN_FAILURE_EXIT = 1;
    private static final Pattern TEST_CLASS = Pattern.compile("[A-Za-z][A-Za-z0-9]*Test");
    private static final Pattern MAVEN_UNAVAILABLE = Pattern.compile(
            "Could not resolve dependencies|Cannot access .* in offline mode|offline mode and the artifact|Could not transfer artifact"
                    + "|Non-resolvable parent POM|Plugin .* could not be resolved|Unknown packaging");
    private static final Pattern COMPILATION_FAILURE = Pattern.compile("COMPILATION ERROR|Compilation failure");
    private static final Pattern TAB = Pattern.compile("\t");

    @FunctionalInterface
    interface Executor {
        String run(Path directory, List<String> args, Duration timeout) throws IOException, InterruptedException;
    }

    /** Whether Docker and the pinned validator image are usable now. */
    public record Preflight(boolean ready, String detail, Instant checkedAt) {}

    private record Suite(String name, int tests, int failures, int errors, int skipped, Element element) {}

    private final Path mavenCache;
    private final Executor executor;
    private final Clock clock;
    private final AtomicReference<Preflight> preflight = new AtomicReference<>();

    public SandboxValidator(Path mavenCache) {
        this(mavenCache, GitWorkspace::command, Clock.systemUTC());
    }

    SandboxValidator(Path mavenCache, Executor executor, Clock clock) {
        this.mavenCache = mavenCache.toAbsolutePath();
        this.executor = executor;
        this.clock = clock;
    }

    /** Cached for a short time so status polling does not spawn Docker processes on every request. */
    public Preflight preflight() {
        Preflight cached = preflight.get();
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.checkedAt().plus(PREFLIGHT_TTL))) return cached;
        Preflight fresh = checkPlatform(now);
        preflight.set(fresh);
        return fresh;
    }

    private Preflight checkPlatform(Instant now) {
        if (!Files.isDirectory(mavenCache)) return new Preflight(false, "Maven cache missing: " + mavenCache, now);
        Path here = Path.of(".").toAbsolutePath();
        try {
            executor.run(here, List.of("docker", "info", "--format", "{{.ServerVersion}}"), DOCKER_TIMEOUT);
        } catch (IOException unavailable) {
            return new Preflight(false, "Docker is not available: start Docker Desktop or Colima", now);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Preflight(false, "Docker check interrupted", now);
        }
        try {
            executor.run(here, List.of("docker", "image", "inspect", "--format", "{{.Id}}", IMAGE), DOCKER_TIMEOUT);
        } catch (IOException missing) {
            return new Preflight(false, "Validator image missing; run: docker pull " + IMAGE, now);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Preflight(false, "Docker check interrupted", now);
        }
        return new Preflight(true, "Docker and validator image available", now);
    }

    private void requireReady() throws InfrastructureException {
        Preflight status = preflight();
        if (!status.ready()) {
            preflight.set(null); // Re-check on the next attempt instead of reusing the failure.
            throw new InfrastructureException(status.detail());
        }
    }

    /**
     * Runs the full suite. Every class in {@code requiredTestClasses} (fully qualified) must have produced
     * a report with at least one executed test, so changed tests cannot silently be skipped.
     */
    public String test(Path candidate, Collection<String> requiredTestClasses)
            throws IOException, InterruptedException {
        requireReady();
        Path reports = reportDirectory(candidate);
        try {
            Instant started = clock.instant();
            try {
                run(candidate, reports, List.of("test"));
            } catch (CommandRunner.Failed failure) {
                throw classify(failure, reports);
            }
            Map<String, Suite> suites = readSuites(reports, started);
            int tests = 0;
            int failures = 0;
            int errors = 0;
            int skipped = 0;
            for (Suite suite : suites.values()) {
                tests += suite.tests();
                failures += suite.failures();
                errors += suite.errors();
                skipped += suite.skipped();
            }
            if (tests == 0 || failures > 0 || errors > 0 || skipped > 0) {
                throw new ValidationFailedException("Invalid test result: tests=" + tests + ", failures=" + failures
                        + ", errors=" + errors + ", skipped=" + skipped);
            }
            for (String required : requiredTestClasses) {
                Suite suite = suites.get(required);
                if (suite == null || suite.tests() == 0) {
                    throw new ValidationFailedException("Changed test class did not execute any test: " + required);
                }
            }
            return "Sandboxed Maven tests passed: executed=" + tests + ", failures=0, errors=0, skipped=0, suites="
                    + suites.size();
        } finally {
            deleteRecursively(reports);
        }
    }

    public String expectRegression(Path candidate, String testClass) throws IOException, InterruptedException {
        if (testClass == null || !TEST_CLASS.matcher(testClass).matches()) {
            throw new IllegalArgumentException("Expected a single regression test class");
        }
        requireReady();
        Path reports = reportDirectory(candidate);
        try {
            Instant started = clock.instant();
            try {
                run(candidate, reports, List.of("-Dtest=" + testClass, "test"));
                throw new ValidationFailedException("Regression was green on the buggy baseline");
            } catch (CommandRunner.Failed expectedFailure) {
                if (expectedFailure.exitCode() != MAVEN_FAILURE_EXIT) throw classify(expectedFailure, reports);
                List<Suite> matches = readSuites(reports, started).values().stream()
                        .filter(suite ->
                                suite.name().equals(testClass) || suite.name().endsWith("." + testClass))
                        .toList();
                if (matches.size() != 1) throw classify(expectedFailure, reports);
                Suite suite = matches.getFirst();
                if (suite.tests() != 1 || suite.failures() != 1 || suite.errors() != 0 || suite.skipped() != 0) {
                    throw new ValidationFailedException(
                            "Expected exactly one failing regression, without test errors or skips");
                }
                var failure = suite.element().getElementsByTagName("failure");
                if (failure.getLength() != 1)
                    throw new ValidationFailedException("Regression report has no single assertion failure");
                return "Expected red regression confirmed: " + testClass
                        + "; tests=1, failures=1, errors=0, skipped=0; assertion="
                        + ((Element) failure.item(0)).getAttribute("message");
            }
        } finally {
            deleteRecursively(reports);
        }
    }

    /**
     * Separates platform problems from reproducible candidate failures.
     *
     * @return the exception to throw for a platform or unclassified failure
     * @throws ValidationFailedException when the candidate failed to compile or its tests failed
     */
    private IOException classify(CommandRunner.Failed failure, Path reports) throws IOException {
        String output = failure.output();
        if (failure.exitCode() >= DOCKER_FAILURE_EXIT) {
            return new InfrastructureException(
                    "Docker could not run the validator (exit " + failure.exitCode() + ")", failure);
        }
        if (MAVEN_UNAVAILABLE.matcher(output).find()) {
            return new InfrastructureException(
                    "Offline Maven cache is missing required artifacts; build the shortener once with network access",
                    failure);
        }
        if (failure.exitCode() == MAVEN_FAILURE_EXIT
                && COMPILATION_FAILURE.matcher(output).find()) {
            throw new ValidationFailedException("Candidate does not compile:\n" + output);
        }
        if (failure.exitCode() == MAVEN_FAILURE_EXIT && hasReports(reports)) {
            throw new ValidationFailedException("Candidate tests failed:\n" + output);
        }
        return failure;
    }

    private static boolean hasReports(Path reports) throws IOException {
        try (var files = Files.list(reports)) {
            return files.anyMatch(SandboxValidator::isReport);
        }
    }

    private static boolean isReport(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith("TEST-") && name.endsWith(".xml");
    }

    private Map<String, Suite> readSuites(Path reports, Instant started) throws IOException {
        Map<String, Suite> suites = new HashMap<>();
        Instant earliest = started.minus(REPORT_CLOCK_TOLERANCE);
        try (var files = Files.list(reports)) {
            for (Path file : files.filter(SandboxValidator::isReport).toList()) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file))
                    throw new PolicyViolationException("Report is not a regular file: " + file.getFileName());
                if (Files.getLastModifiedTime(file).toInstant().isBefore(earliest)) {
                    throw new PolicyViolationException("Report predates this validation run: " + file.getFileName());
                }
                Element element = parseSuite(file);
                String name = element.getAttribute("name");
                suites.put(
                        name,
                        new Suite(
                                name,
                                count(element, "tests"),
                                count(element, "failures"),
                                count(element, "errors"),
                                count(element, "skipped"),
                                element));
            }
        }
        return suites;
    }

    private static int count(Element suite, String attribute) {
        try {
            return Integer.parseInt(suite.getAttribute(attribute));
        } catch (NumberFormatException malformed) {
            throw new ValidationFailedException("Malformed test report attribute: " + attribute);
        }
    }

    private static Element parseSuite(Path report) throws IOException {
        try {
            var parser = DocumentBuilderFactory.newInstance();
            parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            parser.setFeature("http://xml.org/sax/features/external-general-entities", false);
            parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            return parser.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
        } catch (ParserConfigurationException | SAXException malformed) {
            throw new ValidationFailedException("Unreadable test report: " + report.getFileName());
        }
    }

    /** A fresh host-owned directory beside the candidate, so Docker Desktop/Colima can mount it. */
    private static Path reportDirectory(Path candidate) throws IOException {
        return Files.createTempDirectory(candidate.toAbsolutePath().getParent(), "factory-reports-");
    }

    private void run(Path candidate, Path reports, List<String> goals) throws IOException, InterruptedException {
        validateMount(candidate);
        validateMount(mavenCache);
        validateMount(reports);
        Path build = candidate.resolve(GitWorkspace.BUILD_OUTPUT);
        for (Path part = build; !part.equals(candidate); part = part.getParent()) {
            if (Files.isSymbolicLink(part))
                throw new PolicyViolationException("Build directory cannot traverse symlinks");
        }
        Files.createDirectories(build);
        Path trustedPom =
                Files.createTempFile(candidate.toAbsolutePath().getParent(), "factory-validator-pom-", ".xml");
        try (var input = SandboxValidator.class.getResourceAsStream("/validation/shortener-pom.xml")) {
            if (input == null) throw new IllegalStateException("Trusted validator definition missing");
            Files.copy(input, trustedPom, StandardCopyOption.REPLACE_EXISTING);
        }
        validateMount(trustedPom);
        String container = CONTAINER_PREFIX + UUID.randomUUID();
        List<String> args = new ArrayList<>(List.of(
                "docker",
                "run",
                "--rm",
                "--name",
                container,
                "--label",
                OWNER_LABEL + "=" + ProcessHandle.current().pid(),
                "--network",
                "none",
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
                "--env",
                "HOME=/tmp",
                "--env",
                "MAVEN_CONFIG=/tmp/.m2",
                "--mount",
                "type=bind,source=" + candidate.toAbsolutePath() + ",target=/workspace,readonly",
                "--mount",
                "type=bind,source=" + build.toAbsolutePath() + ",target=/workspace/" + GitWorkspace.BUILD_OUTPUT,
                "--mount",
                "type=bind,source=" + reports.toAbsolutePath() + ",target=" + REPORTS_MOUNT,
                "--mount",
                "type=bind,source=" + trustedPom + ",target=/trusted/pom.xml,readonly",
                "--mount",
                "type=bind,source=" + mavenCache + ",target=/m2,readonly",
                "--workdir",
                "/trusted",
                IMAGE,
                "mvn",
                "-o",
                "-q",
                "-Dmaven.repo.local=/m2",
                "-f",
                "/trusted/pom.xml"));
        args.addAll(goals);
        try {
            executor.run(candidate, args, RUN_TIMEOUT);
        } finally {
            removeContainer(candidate, container);
            Files.deleteIfExists(trustedPom);
        }
    }

    /**
     * Killing the Docker CLI on timeout does not stop the container. Remove the uniquely named container
     * even if this thread was interrupted: clear the flag for the cleanup command, then restore it.
     */
    private void removeContainer(Path directory, String container) {
        boolean interrupted = Thread.interrupted();
        try {
            executor.run(directory, List.of("docker", "rm", "-f", container), DOCKER_TIMEOUT);
        } catch (IOException alreadyRemoved) {
            LOG.debug("Validator container {} was already removed: {}", container, alreadyRemoved.getMessage());
        } catch (InterruptedException cleanupInterrupted) {
            interrupted = true;
            LOG.warn("Interrupted while removing validator container {}", container);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Removes validator containers left by processes that no longer exist (or by this process when
     * {@code includeOwn}), e.g. after a crash. Containers of other live factory processes are kept.
     */
    public int removeOrphanedContainers(boolean includeOwn) {
        Path here = Path.of(".").toAbsolutePath();
        long self = ProcessHandle.current().pid();
        int removed = 0;
        try {
            String listing = executor.run(
                    here,
                    List.of(
                            "docker",
                            "ps",
                            "--all",
                            "--filter",
                            "name=" + CONTAINER_PREFIX,
                            "--format",
                            "{{.ID}}\t{{.Names}}\t{{.Label \"" + OWNER_LABEL + "\"}}"),
                    DOCKER_TIMEOUT);
            for (String line : listing.lines().filter(value -> !value.isBlank()).toList()) {
                String[] fields = TAB.split(line, -1);
                if (fields.length != 3 || !fields[1].startsWith(CONTAINER_PREFIX)) continue;
                if (orphaned(fields[2], self, includeOwn)) {
                    executor.run(here, List.of("docker", "rm", "-f", fields[0]), DOCKER_TIMEOUT);
                    removed++;
                }
            }
        } catch (IOException unavailable) {
            LOG.debug("Skipped validator container sweep; Docker unavailable: {}", unavailable.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (removed > 0) LOG.warn("Removed {} orphaned validator container(s)", removed);
        return removed;
    }

    private static boolean orphaned(String ownerLabel, long self, boolean includeOwn) {
        long owner;
        try {
            owner = Long.parseLong(ownerLabel.strip());
        } catch (NumberFormatException unlabelled) {
            return true; // Created before ownership labels; --rm containers never outlive their run.
        }
        if (owner == self) return includeOwn;
        return ProcessHandle.of(owner).map(handle -> !handle.isAlive()).orElse(true);
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

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
