package dev.softwarefactory.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.softwarefactory.candidate.CommandRunner;
import dev.softwarefactory.governance.PolicyViolationException;
import dev.softwarefactory.platform.InfrastructureException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxValidatorTest {
    private static final String RED_REPORT =
            "<testsuite name='demo.RegressionTest' tests='1' failures='1' errors='0' skipped='0'>"
                    + "<testcase><failure message='expected failure'/></testcase></testsuite>";
    private static final String GREEN_REPORT =
            "<testsuite name='demo.ExistingTest' tests='2' failures='0' errors='0' skipped='0'/>";

    @TempDir
    Path root;

    private Path candidate;
    private final List<List<String>> commands = new ArrayList<>();

    @BeforeEach
    void candidate() throws IOException {
        candidate = Files.createDirectories(root.resolve("candidate"));
    }

    @FunctionalInterface
    interface Maven {
        void run(Path reports) throws IOException;
    }

    /** Simulates Docker: writes reports into the host directory mounted at /reports, then runs {@code maven}. */
    private SandboxValidator validator(Maven maven) {
        return new SandboxValidator(
                root,
                (directory, args, timeout) -> {
                    commands.add(args);
                    if (!args.contains("run")) return "";
                    maven.run(reportsMount(args));
                    return "";
                },
                Clock.systemUTC());
    }

    private static Path reportsMount(List<String> args) {
        return args.stream()
                .filter(arg -> arg.endsWith(",target=" + DockerSandbox.REPORTS_MOUNT))
                .map(arg -> Path.of(arg.substring("type=bind,source=".length(), arg.indexOf(",target="))))
                .findFirst()
                .orElseThrow();
    }

    private static void write(Path reports, String suite, String xml) throws IOException {
        Files.writeString(reports.resolve("TEST-" + suite + ".xml"), xml);
    }

    @Test
    void timeoutAndDockerFailureNeverCountAsRedEvenWithAnAssertionReport() {
        assertThrows(
                IOException.class,
                () -> validator(reports -> {
                            write(reports, "demo.RegressionTest", RED_REPORT);
                            throw new IOException("timeout");
                        })
                        .expectRegression(candidate, "RegressionTest"));
        assertThrows(
                InfrastructureException.class,
                () -> validator(reports -> {
                            write(reports, "demo.RegressionTest", RED_REPORT);
                            throw new CommandRunner.Failed(125, "Docker unavailable");
                        })
                        .expectRegression(candidate, "RegressionTest"));
    }

    @Test
    void requiresACompletedFailingTestCommand() throws Exception {
        String red = validator(reports -> {
                    write(reports, "demo.RegressionTest", RED_REPORT);
                    throw new CommandRunner.Failed(1, "test failure");
                })
                .expectRegression(candidate, "RegressionTest");
        assertTrue(red.contains("Expected red regression"));
        assertThrows(
                IllegalArgumentException.class, () -> DockerSandbox.validateMount(Path.of("/tmp/source,target=/etc")));
    }

    @Test
    void reportsAreWrittenOutsideTheCandidateAndRemovedAfterwards() throws Exception {
        String result = validator(reports -> write(reports, "demo.ExistingTest", GREEN_REPORT))
                .runSuite(candidate, Set.of());
        assertTrue(result.contains("executed=2"));
        List<String> run = commands.stream()
                .filter(args -> args.contains("run"))
                .findFirst()
                .orElseThrow();
        Path reports = reportsMount(run);
        assertFalse(reports.startsWith(candidate), "Candidate code must not own the report directory");
        assertFalse(Files.exists(reports));
        assertTrue(run.contains("--label"));
        assertTrue(run.contains(SandboxValidator.IMAGE));
    }

    @Test
    void changedTestClassesMustProduceExecutedReports() {
        var validator = validator(reports -> write(reports, "demo.ExistingTest", GREEN_REPORT));
        var missing = assertThrows(
                ValidationFailedException.class, () -> validator.runSuite(candidate, Set.of("demo.AddedTest")));
        assertTrue(missing.getMessage().contains("demo.AddedTest"));
    }

    @Test
    void staleReportsAreRejected() {
        var validator = validator(reports -> {
            write(reports, "demo.ExistingTest", GREEN_REPORT);
            Files.setLastModifiedTime(
                    reports.resolve("TEST-demo.ExistingTest.xml"),
                    FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        });
        assertThrows(PolicyViolationException.class, () -> validator.runSuite(candidate, Set.of()));
    }

    @Test
    void failingTestsAreDeterministicButMissingArtifactsAreInfrastructure() {
        assertThrows(
                ValidationFailedException.class,
                () -> validator(reports -> {
                            write(
                                    reports,
                                    "demo.ExistingTest",
                                    "<testsuite name='demo.ExistingTest' tests='1' failures='1' errors='0' skipped='0'/>");
                            throw new CommandRunner.Failed(1, "There are test failures");
                        })
                        .runSuite(candidate, Set.of()));
        assertThrows(
                ValidationFailedException.class,
                () -> validator(reports -> {
                            throw new CommandRunner.Failed(1, "[ERROR] COMPILATION ERROR :");
                        })
                        .runSuite(candidate, Set.of()));
        assertThrows(
                InfrastructureException.class,
                () -> validator(reports -> {
                            throw new CommandRunner.Failed(
                                    1, "Could not resolve dependencies for project dev.softwarefactory:shortener");
                        })
                        .runSuite(candidate, Set.of()));
    }

    @Test
    void missingDockerOrImageIsReportedByPreflightWithoutRunningMaven() {
        var runs = new AtomicBoolean();
        var validator = new SandboxValidator(
                root,
                (directory, args, timeout) -> {
                    if (args.contains("image")) throw new CommandRunner.Failed(1, "No such image");
                    if (args.contains("run")) runs.set(true);
                    return "";
                },
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        assertFalse(validator.preflight().ready());
        assertTrue(validator.preflight().detail().contains("docker pull"));
        assertThrows(InfrastructureException.class, () -> validator.runSuite(candidate, Set.of()));
        assertFalse(runs.get());
    }

    @Test
    void containerCleanupRunsEvenWhenTheThreadIsInterrupted() {
        var cleanupInterrupted = new AtomicBoolean(true);
        var validator = new SandboxValidator(
                root,
                (directory, args, timeout) -> {
                    if (args.contains("rm"))
                        cleanupInterrupted.set(Thread.currentThread().isInterrupted());
                    if (args.contains("run")) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedException("operator shutdown");
                    }
                    return "";
                },
                Clock.systemUTC());
        try {
            assertThrows(InterruptedException.class, () -> validator.runSuite(candidate, Set.of()));
            assertFalse(cleanupInterrupted.get(), "docker rm must run with the interrupt flag cleared");
            assertTrue(Thread.currentThread().isInterrupted(), "The interrupt must be restored for the caller");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void sweepRemovesOnlyContainersOfDeadOrUnlabelledOwners() {
        long self = ProcessHandle.current().pid();
        var removed = new ArrayList<String>();
        var validator = new SandboxValidator(
                root,
                (directory, args, timeout) -> {
                    if (args.contains("ps")) {
                        return "a1\tfactory-validator-1\t" + self
                                + "\nb2\tfactory-validator-2\t999999999\nc3\tfactory-validator-3\t\n"
                                + "d4\tunrelated\t1\n";
                    }
                    if (args.contains("rm")) removed.add(args.getLast());
                    return "";
                },
                Clock.systemUTC());
        assertEquals(2, validator.sweepOrphanedContainers(false));
        assertEquals(List.of("b2", "c3"), removed);
        removed.clear();
        assertEquals(3, validator.sweepOrphanedContainers(true));
    }
}
