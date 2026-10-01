package dev.softwarefactory.governance;

import dev.softwarefactory.execution.PolicyViolationException;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps generated changes from weakening the regression suite structurally: existing tests cannot be
 * deleted, and new tests cannot opt out of the default (non-integration) validation run.
 */
public final class TestChangePolicy {
    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(\\S+) b/(\\S+)$");
    /** Surefire's default inclusion patterns, rooted at a Maven test source directory. */
    private static final Pattern TEST_SOURCE = Pattern.compile(
        "(?:.*/)?src/test/java/((?:[A-Za-z_$][A-Za-z0-9_$]*/)*)((?:Test[A-Za-z0-9_$]*)|(?:[A-Za-z_$][A-Za-z0-9_$]*(?:Test|Tests|TestCase)))\\.java");
    private static final Pattern EXCLUDED_TAG = Pattern.compile("^\\+.*@Tag\\s*\\(\\s*\"integration\"\\s*\\)");

    private TestChangePolicy() {}

    public static void check(String patch) {
        String path = null;
        for (String line : patch.lines().toList()) {
            Matcher header = DIFF_HEADER.matcher(line);
            if (header.matches()) {
                path = header.group(1);
                continue;
            }
            if (path == null || (!path.contains("/src/test/") && !path.startsWith("src/test/"))) continue;
            boolean deletion = line.startsWith("deleted file mode") || line.equals("+++ /dev/null");
            if (deletion && TEST_SOURCE.matcher(path).matches()) {
                throw new PolicyViolationException("Existing tests cannot be deleted: " + path);
            }
            if (EXCLUDED_TAG.matcher(line).find()) {
                throw new PolicyViolationException("Generated tests cannot opt out of validation with @Tag(\"integration\"): " + path);
            }
        }
    }

    /** Fully qualified names of test classes this patch adds or modifies. */
    public static Set<String> testClasses(String patch) {
        Set<String> classes = new LinkedHashSet<>();
        for (String line : patch.lines().toList()) {
            Matcher header = DIFF_HEADER.matcher(line);
            if (!header.matches()) continue;
            Matcher source = TEST_SOURCE.matcher(header.group(2));
            if (source.matches()) classes.add(source.group(1).replace('/', '.') + source.group(2));
        }
        return classes;
    }
}
