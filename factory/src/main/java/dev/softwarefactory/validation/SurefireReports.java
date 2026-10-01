package dev.softwarefactory.validation;

import dev.softwarefactory.governance.PolicyViolationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/** Reads the Surefire XML reports a validation run wrote; process output is never trusted for results. */
final class SurefireReports {
    /** Container and host clocks can disagree slightly about report modification times. */
    private static final Duration CLOCK_TOLERANCE = Duration.ofSeconds(2);

    private SurefireReports() {}

    record Suite(String name, int tests, int failures, int errors, int skipped, Element element) {
        boolean hasSingleAssertionFailure() {
            return tests == 1 && failures == 1 && errors == 0 && skipped == 0;
        }
    }

    record Totals(int tests, int failures, int errors, int skipped) {
        static Totals of(Iterable<Suite> suites) {
            int tests = 0;
            int failures = 0;
            int errors = 0;
            int skipped = 0;
            for (Suite suite : suites) {
                tests += suite.tests();
                failures += suite.failures();
                errors += suite.errors();
                skipped += suite.skipped();
            }
            return new Totals(tests, failures, errors, skipped);
        }

        boolean allPassed() {
            return tests > 0 && failures == 0 && errors == 0 && skipped == 0;
        }
    }

    static boolean exist(Path reports) throws IOException {
        try (var files = Files.list(reports)) {
            return files.anyMatch(SurefireReports::isReport);
        }
    }

    /** Suites by name. Reports must be regular files written by this run, not planted by the candidate. */
    static Map<String, Suite> read(Path reports, Instant started) throws IOException {
        Map<String, Suite> suites = new HashMap<>();
        Instant earliest = started.minus(CLOCK_TOLERANCE);
        try (var files = Files.list(reports)) {
            for (Path file : files.filter(SurefireReports::isReport).toList()) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file))
                    throw new PolicyViolationException("Report is not a regular file: " + file.getFileName());
                if (Files.getLastModifiedTime(file).toInstant().isBefore(earliest)) {
                    throw new PolicyViolationException("Report predates this validation run: " + file.getFileName());
                }
                Suite suite = suite(parse(file));
                suites.put(suite.name(), suite);
            }
        }
        return suites;
    }

    private static boolean isReport(Path path) {
        Path name = path.getFileName();
        return name != null
                && name.toString().startsWith("TEST-")
                && name.toString().endsWith(".xml");
    }

    private static Suite suite(Element element) {
        return new Suite(
                element.getAttribute("name"),
                count(element, "tests"),
                count(element, "failures"),
                count(element, "errors"),
                count(element, "skipped"),
                element);
    }

    private static int count(Element suite, String attribute) {
        try {
            return Integer.parseInt(suite.getAttribute(attribute));
        } catch (NumberFormatException malformed) {
            throw new ValidationFailedException("Malformed test report attribute: " + attribute, malformed);
        }
    }

    private static Element parse(Path report) throws IOException {
        try {
            var parser = DocumentBuilderFactory.newInstance();
            parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            parser.setFeature("http://xml.org/sax/features/external-general-entities", false);
            parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            return parser.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
        } catch (ParserConfigurationException | SAXException malformed) {
            throw new ValidationFailedException("Unreadable test report: " + report.getFileName(), malformed);
        }
    }
}
