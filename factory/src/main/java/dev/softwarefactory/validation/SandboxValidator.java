package dev.softwarefactory.validation;

import dev.softwarefactory.candidate.CommandRunner;
import dev.softwarefactory.platform.InfrastructureException;
import dev.softwarefactory.validation.SurefireReports.Suite;
import dev.softwarefactory.validation.SurefireReports.Totals;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

/**
 * Executes candidate build logic without governance credentials or network access, and separates
 * platform problems (pause, retry later) from reproducible candidate failures (revise upstream work).
 */
public final class SandboxValidator implements CandidateValidator {
    /** Pinned by digest: a tag could be re-pointed at different build tooling. */
    public static final String IMAGE = "maven@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320";

    private static final Duration PREFLIGHT_TTL = Duration.ofSeconds(30);
    private static final int DOCKER_FAILURE_EXIT = 125;
    private static final int MAVEN_FAILURE_EXIT = 1;
    private static final Pattern TEST_CLASS = Pattern.compile("[A-Za-z][A-Za-z0-9]*Test");
    private static final Pattern MAVEN_UNAVAILABLE = Pattern.compile(
            "Could not resolve dependencies|Cannot access .* in offline mode|offline mode and the artifact|Could not transfer artifact"
                    + "|Non-resolvable parent POM|Plugin .* could not be resolved|Unknown packaging");
    private static final Pattern COMPILATION_FAILURE = Pattern.compile("COMPILATION ERROR|Compilation failure");

    private final DockerSandbox sandbox;
    private final Clock clock;
    private final AtomicReference<Preflight> preflight = new AtomicReference<>();

    public SandboxValidator(Path mavenCache) {
        this(mavenCache, CommandRunner::checked, Clock.systemUTC());
    }

    SandboxValidator(Path mavenCache, HostCommand host, Clock clock) {
        this.sandbox = new DockerSandbox(mavenCache, host);
        this.clock = clock;
    }

    /** Cached for a short time so status polling does not spawn Docker processes on every request. */
    @Override
    public Preflight preflight() {
        Preflight cached = preflight.get();
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.checkedAt().plus(PREFLIGHT_TTL))) return cached;
        Preflight fresh = sandbox.check(now);
        preflight.set(fresh);
        return fresh;
    }

    @Override
    public String runSuite(Path candidate, Collection<String> requiredTestClasses)
            throws IOException, InterruptedException {
        requireReady();
        Path reports = reportDirectory(candidate);
        try {
            Instant started = clock.instant();
            CommandRunner.Failed failure = mavenFailure(candidate, reports, List.of("test"));
            if (failure != null) throw classify(failure, reports);
            Map<String, Suite> suites = SurefireReports.read(reports, started);
            Totals totals = Totals.of(suites.values());
            if (!totals.allPassed()) {
                throw new ValidationFailedException("Invalid test result: tests=" + totals.tests() + ", failures="
                        + totals.failures() + ", errors=" + totals.errors() + ", skipped=" + totals.skipped());
            }
            for (String required : requiredTestClasses) {
                Suite suite = suites.get(required);
                if (suite == null || suite.tests() == 0) {
                    throw new ValidationFailedException("Changed test class did not execute any test: " + required);
                }
            }
            return "Sandboxed Maven tests passed: executed=" + totals.tests()
                    + ", failures=0, errors=0, skipped=0, suites=" + suites.size();
        } finally {
            deleteRecursively(reports);
        }
    }

    @Override
    public String expectRegression(Path candidate, String testClass) throws IOException, InterruptedException {
        requireTestClassName(testClass);
        requireReady();
        Path reports = reportDirectory(candidate);
        try {
            Instant started = clock.instant();
            CommandRunner.Failed failure = mavenFailure(candidate, reports, List.of("-Dtest=" + testClass, "test"));
            if (failure == null) throw new ValidationFailedException("Regression was green on the buggy baseline");
            if (failure.exitCode() != MAVEN_FAILURE_EXIT) throw classify(failure, reports);
            List<Suite> matches = suitesOf(testClass, reports, started);
            if (matches.size() != 1) throw classify(failure, reports);
            return "Expected red regression confirmed: " + testClass
                    + "; tests=1, failures=1, errors=0, skipped=0; assertion=" + assertionMessage(matches.getFirst());
        } finally {
            deleteRecursively(reports);
        }
    }

    @Override
    public int sweepOrphanedContainers(boolean includeOwn) {
        return sandbox.sweepOrphanedContainers(includeOwn);
    }

    /** The name is passed to Maven as {@code -Dtest=}, so it must be a plain class name and nothing else. */
    private static void requireTestClassName(String testClass) {
        if (testClass == null || !TEST_CLASS.matcher(testClass).matches()) {
            throw new IllegalArgumentException("Expected a single regression test class");
        }
    }

    private void requireReady() throws InfrastructureException {
        Preflight status = preflight();
        if (!status.ready()) {
            preflight.set(null); // Re-check on the next attempt instead of reusing the failure.
            throw new InfrastructureException(status.detail());
        }
    }

    /** The build's non-zero exit, or null when Maven succeeded. */
    private CommandRunner.Failed mavenFailure(Path candidate, Path reports, List<String> goals)
            throws IOException, InterruptedException {
        try {
            sandbox.maven(candidate, reports, goals);
            return null;
        } catch (CommandRunner.Failed failure) {
            return failure;
        }
    }

    private static List<Suite> suitesOf(String testClass, Path reports, Instant started) throws IOException {
        return SurefireReports.read(reports, started).values().stream()
                .filter(suite -> suite.name().equals(testClass) || suite.name().endsWith("." + testClass))
                .toList();
    }

    private static String assertionMessage(Suite suite) {
        if (!suite.hasSingleAssertionFailure()) {
            throw new ValidationFailedException(
                    "Expected exactly one failing regression, without test errors or skips");
        }
        var failure = suite.element().getElementsByTagName("failure");
        if (failure.getLength() != 1)
            throw new ValidationFailedException("Regression report has no single assertion failure");
        return ((Element) failure.item(0)).getAttribute("message");
    }

    /**
     * Separates platform problems from reproducible candidate failures.
     *
     * @return the exception to throw for a platform or unclassified failure
     * @throws ValidationFailedException when the candidate failed to compile or its tests failed
     */
    private static IOException classify(CommandRunner.Failed failure, Path reports) throws IOException {
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
        if (failure.exitCode() == MAVEN_FAILURE_EXIT) {
            if (COMPILATION_FAILURE.matcher(output).find()) {
                throw new ValidationFailedException("Candidate does not compile:\n" + output, failure);
            }
            if (SurefireReports.exist(reports)) {
                throw new ValidationFailedException("Candidate tests failed:\n" + output, failure);
            }
        }
        return failure;
    }

    /** A fresh host-owned directory beside the candidate, so the candidate cannot plant reports in it. */
    private static Path reportDirectory(Path candidate) throws IOException {
        return Files.createTempDirectory(DockerSandbox.siblingDirectory(candidate), "factory-reports-");
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
