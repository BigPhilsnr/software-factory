package dev.softwarefactory.validation;

import dev.softwarefactory.governance.PolicyViolationException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps generated changes from weakening the regression suite structurally: existing tests cannot be
 * deleted, and new tests cannot opt out of the default (non-integration) validation run.
 */
public final class GeneratedTestPolicy {
    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(\\S+) b/(\\S+)$");
    private static final Pattern EXCLUDED_TAG = Pattern.compile("^\\+.*@Tag\\s*\\(\\s*\"integration\"\\s*\\)");
    private static final String TEST_ROOT = "src/test/java/";
    private static final String JAVA_SUFFIX = ".java";
    private static final String NEW_FILE_PREFIX = "+++ b/";
    private static final String PRODUCT_TEST_ROOT = "shortener/" + TEST_ROOT;
    /** Surefire's default inclusion patterns. */
    private static final List<String> TEST_CLASS_SUFFIXES = List.of("Test", "Tests", "TestCase");

    private GeneratedTestPolicy() {}

    /** A test class in a Maven test source directory, as Surefire would select it by default. */
    private record TestSource(String packagePath, String className) {
        String qualifiedName() {
            return packagePath.replace('/', '.') + className;
        }

        /**
         * Parses {@code [dir/]src/test/java/[package/]Name.java} without regular-expression backtracking.
         * The last test root in the path wins, as each segment after it must be a Java identifier.
         */
        static TestSource parse(String path) {
            if (!path.endsWith(JAVA_SUFFIX)) return null;
            String withoutSuffix = path.substring(0, path.length() - JAVA_SUFFIX.length());
            for (int root = withoutSuffix.lastIndexOf(TEST_ROOT);
                    root >= 0;
                    root = withoutSuffix.lastIndexOf(TEST_ROOT, root - 1)) {
                if (root == 0 || withoutSuffix.charAt(root - 1) == '/') {
                    TestSource source = below(withoutSuffix.substring(root + TEST_ROOT.length()));
                    if (source != null) return source;
                }
            }
            return null;
        }

        private static TestSource below(String relative) {
            int split = relative.lastIndexOf('/') + 1;
            String className = relative.substring(split);
            String packagePath = relative.substring(0, split);
            if (!isIdentifier(className) || !isSurefireTestName(className)) return null;
            if (!packagePath.isEmpty()) {
                for (String segment : packagePath.substring(0, split - 1).split("/", -1)) {
                    if (!isIdentifier(segment)) return null;
                }
            }
            return new TestSource(packagePath, className);
        }

        private static boolean isSurefireTestName(String name) {
            return name.startsWith("Test")
                    || TEST_CLASS_SUFFIXES.stream()
                            .anyMatch(suffix -> name.length() > suffix.length() && name.endsWith(suffix));
        }
    }

    /**
     * @throws PolicyViolationException when the patch deletes a test or tags one out of the default run
     */
    public static void check(String patch) {
        String path = null;
        for (String line : patch.lines().toList()) {
            Matcher header = DIFF_HEADER.matcher(line);
            if (header.matches()) {
                path = header.group(1);
            } else if (path != null && isUnderTestSources(path)) {
                requireSuiteKept(path, line);
            }
        }
    }

    /** Fully qualified names of test classes this patch adds or modifies. */
    public static Set<String> changedTestClasses(String patch) {
        Set<String> classes = new LinkedHashSet<>();
        for (String line : patch.lines().toList()) {
            Matcher header = DIFF_HEADER.matcher(line);
            if (!header.matches()) continue;
            TestSource source = TestSource.parse(header.group(2));
            if (source != null) classes.add(source.qualifiedName());
        }
        return classes;
    }

    /**
     * The simple name of the one product test class a regression patch adds or changes.
     *
     * @throws IllegalArgumentException unless the patch contains exactly one such class
     */
    public static String regressionTestClass(String patch) {
        List<String> classes = new ArrayList<>();
        for (String line : patch.lines().toList()) {
            String name = productTestClass(line);
            if (name != null) classes.add(name);
        }
        if (classes.isEmpty()) throw new IllegalArgumentException("Regression patch has no Java test class");
        if (classes.size() > 1)
            throw new IllegalArgumentException("Regression patch must contain exactly one Java test class");
        return classes.getFirst();
    }

    /** {@code +++ b/shortener/src/test/java/[package/]NameTest.java} yields {@code NameTest}. */
    private static String productTestClass(String line) {
        if (!line.startsWith(NEW_FILE_PREFIX + PRODUCT_TEST_ROOT) || !line.endsWith("Test" + JAVA_SUFFIX)) return null;
        String relative = line.substring(
                NEW_FILE_PREFIX.length() + PRODUCT_TEST_ROOT.length(), line.length() - JAVA_SUFFIX.length());
        String[] segments = relative.split("/", -1);
        for (int index = 0; index < segments.length - 1; index++) {
            if (!isWord(segments[index])) return null;
        }
        String className = segments[segments.length - 1];
        boolean named = className.length() > "Test".length()
                && isLetter(className.charAt(0))
                && className.chars().allMatch(c -> isLetter(c) || isDigit(c));
        return named ? className : null;
    }

    private static boolean isUnderTestSources(String path) {
        return path.contains("/src/test/") || path.startsWith("src/test/");
    }

    private static void requireSuiteKept(String path, String line) {
        boolean deletion = line.startsWith("deleted file mode") || "+++ /dev/null".equals(line);
        if (deletion && TestSource.parse(path) != null) {
            throw new PolicyViolationException("Existing tests cannot be deleted: " + path);
        }
        if (EXCLUDED_TAG.matcher(line).find()) {
            throw new PolicyViolationException(
                    "Generated tests cannot opt out of validation with @Tag(\"integration\"): " + path);
        }
    }

    /** ASCII letters, digits, underscore and dollar; not starting with a digit. */
    private static boolean isIdentifier(String text) {
        if (text.isEmpty() || isDigit(text.charAt(0))) return false;
        return text.chars().allMatch(c -> isLetter(c) || isDigit(c) || c == '_' || c == '$');
    }

    /** One or more ASCII letters, digits or underscores: a package directory in a regression patch. */
    private static boolean isWord(String text) {
        return !text.isEmpty() && text.chars().allMatch(c -> isLetter(c) || isDigit(c) || c == '_');
    }

    private static boolean isLetter(int c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }
}
