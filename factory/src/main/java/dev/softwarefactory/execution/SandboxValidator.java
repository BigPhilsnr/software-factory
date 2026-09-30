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

    public SandboxValidator(Path mavenCache) { this.mavenCache = mavenCache.toAbsolutePath(); }

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
        } catch (IOException expectedFailure) {
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
        List<String> args = new ArrayList<>(List.of(
            "docker", "run", "--rm", "--network", "none", "--cpus", "2", "--memory", "1g",
            "--pids-limit", "128", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
            "--read-only", "--user", currentUser(), "--tmpfs", "/tmp:rw,nosuid,size=128m",
            "--env", "HOME=/tmp", "--env", "MAVEN_CONFIG=/tmp/.m2",
            "--mount", "type=bind,source=" + candidate.toAbsolutePath() + ",target=/workspace",
            "--mount", "type=bind,source=" + mavenCache + ",target=/m2,readonly",
            "--workdir", "/workspace", "maven:3.9-eclipse-temurin-21",
            "mvn", "-o", "-q", "-Dmaven.repo.local=/m2", "-f", "shortener/pom.xml"));
        args.addAll(goals);
        return GitWorkspace.command(candidate, args, Duration.ofMinutes(10));
    }

    private static String currentUser() {
        var user = new com.sun.security.auth.module.UnixSystem();
        return user.getUid() + ":" + user.getGid();
    }
}
