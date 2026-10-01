package dev.softwarefactory.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Executes candidate build logic without governance credentials or network access. */
public final class SandboxValidator {
    private final Path mavenCache;
    @FunctionalInterface interface Executor { String run(Path directory, List<String> args, Duration timeout) throws Exception; }
    private final Executor executor;

    public SandboxValidator(Path mavenCache) { this(mavenCache, GitWorkspace::command); }
    SandboxValidator(Path mavenCache, Executor executor) { this.mavenCache = mavenCache.toAbsolutePath(); this.executor = executor; }

    public String test(Path candidate) throws Exception {
        clearReports(candidate);
        run(candidate, List.of("test"));
        Path reports = candidate.resolve("shortener/target/surefire-reports");
        int tests = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        try (var files = Files.list(reports)) {
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith("TEST-") && path.toString().endsWith(".xml")).toList()) {
                var suite = parseSuite(file);
                tests += Integer.parseInt(suite.getAttribute("tests"));
                failures += Integer.parseInt(suite.getAttribute("failures"));
                errors += Integer.parseInt(suite.getAttribute("errors"));
                skipped += Integer.parseInt(suite.getAttribute("skipped"));
            }
        }
        if (tests == 0 || failures > 0 || errors > 0 || skipped > 0) {
            throw new IllegalStateException("Invalid test result: tests=" + tests + ", failures=" + failures +
                ", errors=" + errors + ", skipped=" + skipped);
        }
        return "Sandboxed Maven tests passed: executed=" + tests + ", failures=0, errors=0, skipped=0";
    }

    public String expectRegression(Path candidate, String testClass) throws Exception {
        if (testClass == null || !testClass.matches("[A-Za-z][A-Za-z0-9]*Test")) {
            throw new IllegalArgumentException("Expected a single regression test class");
        }
        clearReports(candidate);
        try {
            run(candidate, List.of("-Dtest=" + testClass, "test"));
            throw new IllegalStateException("Regression was green on the buggy baseline");
        } catch (CommandRunner.Failed expectedFailure) {
            if (expectedFailure.exitCode() != 1) throw expectedFailure;
            Path reports = candidate.resolve("shortener/target/surefire-reports");
            if (!Files.isDirectory(reports)) throw expectedFailure;
            List<Path> matches;
            try (var files = Files.list(reports)) {
                matches = files.filter(path -> path.getFileName().toString().startsWith("TEST-")
                    && path.getFileName().toString().endsWith("." + testClass + ".xml")).toList();
            }
            if (matches.size() != 1) throw expectedFailure;
            Path report = matches.getFirst();
            var suite = parseSuite(report);
            if (!"1".equals(suite.getAttribute("tests")) || !"1".equals(suite.getAttribute("failures")) ||
                !"0".equals(suite.getAttribute("errors")) || !"0".equals(suite.getAttribute("skipped"))) {
                throw new IllegalStateException("Expected exactly one failing regression, without test errors or skips");
            }
            var failure = suite.getElementsByTagName("failure");
            if (failure.getLength() != 1) throw new IllegalStateException("Regression report has no single assertion failure");
            return "Expected red regression confirmed: " + testClass + "; tests=1, failures=1, errors=0, skipped=0; assertion=" +
                ((org.w3c.dom.Element) failure.item(0)).getAttribute("message");
        }
    }

    private void clearReports(Path candidate) throws IOException {
        Path reports = candidate.resolve("shortener/target/surefire-reports");
        for (Path part = reports; !part.equals(candidate); part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new SecurityException("Report directory cannot traverse symlinks");
        }
        if (!Files.isDirectory(reports)) return;
        try (var files = Files.list(reports)) {
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith("TEST-") && path.toString().endsWith(".xml")).toList()) {
                Files.delete(file);
            }
        }
    }

    private org.w3c.dom.Element parseSuite(Path report) throws Exception {
        var parser = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        parser.setFeature("http://xml.org/sax/features/external-general-entities", false);
        parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return parser.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
    }

    private String run(Path candidate, List<String> goals) throws Exception {
        validateMount(candidate);
        validateMount(mavenCache);
        Path build = candidate.resolve("shortener/target");
        Files.createDirectories(build);
        Path trustedPom = Files.createTempFile(candidate.toAbsolutePath().getParent(), "factory-validator-pom-", ".xml");
        try (var input = SandboxValidator.class.getResourceAsStream("/validation/shortener-pom.xml")) {
            if (input == null) throw new IllegalStateException("Trusted validator definition missing");
            Files.copy(input, trustedPom, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        validateMount(trustedPom);
        String container = "factory-validator-" + java.util.UUID.randomUUID();
        List<String> args = new ArrayList<>(List.of(
            "docker", "run", "--rm", "--name", container, "--network", "none", "--cpus", "2", "--memory", "1g",
            "--pids-limit", "128", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
            "--read-only", "--user", currentUser(), "--tmpfs", "/tmp:rw,nosuid,size=128m",
            "--env", "HOME=/tmp", "--env", "MAVEN_CONFIG=/tmp/.m2",
            "--mount", "type=bind,source=" + candidate.toAbsolutePath() + ",target=/workspace,readonly",
            "--mount", "type=bind,source=" + build.toAbsolutePath() + ",target=/workspace/shortener/target",
            "--mount", "type=bind,source=" + trustedPom + ",target=/trusted/pom.xml,readonly",
            "--mount", "type=bind,source=" + mavenCache + ",target=/m2,readonly",
            "--workdir", "/trusted", "maven@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320",
            "mvn", "-o", "-q", "-Dmaven.repo.local=/m2", "-f", "/trusted/pom.xml"));
        args.addAll(goals);
        try {
            return executor.run(candidate, args, Duration.ofMinutes(10));
        } finally {
            // Killing the Docker CLI on timeout does not itself stop the container.
            // Remove only the uniquely named container owned by this invocation.
            try { executor.run(candidate, List.of("docker", "rm", "-f", container), Duration.ofSeconds(20)); }
            catch (Exception alreadyRemovedOrUnavailable) { /* --rm normally removed it already. */ }
            Files.deleteIfExists(trustedPom);
        }
    }

    static void validateMount(Path path) {
        String value = path.toAbsolutePath().toString();
        if (value.contains(",") || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Mount paths cannot contain commas or control characters");
    }

    private static String currentUser() {
        var user = new com.sun.security.auth.module.UnixSystem();
        return user.getUid() + ":" + user.getGid();
    }
}
