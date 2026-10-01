package dev.softwarefactory.governance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Worker authority over paths: a generated patch may only edit plain files inside its task's write scope. */
public final class PatchScope {
    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern LINK_OR_SUBMODULE =
            Pattern.compile("(?:new file mode|old mode|new mode|deleted file mode|index [^ ]+) (?:120000|160000)");
    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9_./-]+");
    private static final String GIT_METADATA_PREFIX = ".git";
    private static final String GIT_IGNORE = ".gitignore";
    private static final String BUILD_OUTPUT = "target";
    private static final String ENVIRONMENT_FILE = ".env";

    private PatchScope() {}

    /**
     * @return the paths the patch changes, each inside {@code allowed}
     * @throws PolicyViolationException when the patch reaches outside worker authority
     * @throws IllegalArgumentException when the patch changes nothing recognizable
     */
    public static List<String> changedPaths(String patch, List<String> allowed) {
        if (patch.lines().anyMatch(line -> LINK_OR_SUBMODULE.matcher(line).matches())) {
            throw new PolicyViolationException("Symlinks and submodules are outside worker authority");
        }
        List<String> changed = new ArrayList<>();
        for (String line : patch.lines().toList()) {
            String path = changedPath(line);
            if (path == null) continue;
            if (!isWritable(path, allowed)) throw new PolicyViolationException("Patch outside approved scope: " + path);
            changed.add(path);
        }
        if (changed.isEmpty()) throw new IllegalArgumentException("Patch has no recognized file changes");
        return changed;
    }

    /** The path named by a diff header line, or null for any other line. */
    private static String changedPath(String line) {
        if (line.startsWith("rename ") || line.startsWith("copy ")) {
            throw new PolicyViolationException("Renames and copies are outside worker authority");
        }
        var header = DIFF_HEADER.matcher(line);
        if (!header.matches()) {
            if (line.startsWith("diff --git ")) throw new PolicyViolationException("Ambiguous or quoted diff path");
            return null;
        }
        if (!header.group(1).equals(header.group(2)))
            throw new PolicyViolationException("Renames are outside worker authority");
        return header.group(1);
    }

    private static boolean isWritable(String path, List<String> allowed) {
        return SAFE_PATH.matcher(path).matches()
                && !path.startsWith("/")
                && !path.contains("..")
                && !isProtectedPath(path)
                && allowed.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    /**
     * Paths a worker may never write, even inside its scope: build output (which Git ignores and the
     * validator overwrites), Git metadata and attributes that change how content is recorded or diffed,
     * environment files and logs. A {@code .gitignore} is allowed because release evidence is recorded
     * with ignore rules disabled; {@code PatchPolicy} still routes it to operator approval.
     */
    static boolean isProtectedPath(String path) {
        String[] segments = path.split("/");
        for (String segment : segments) {
            String lower = segment.toLowerCase(Locale.ROOT);
            if (BUILD_OUTPUT.equals(lower) || lower.startsWith(GIT_METADATA_PREFIX) && !GIT_IGNORE.equals(lower)) {
                return true;
            }
        }
        String name = segments[segments.length - 1].toLowerCase(Locale.ROOT);
        return ENVIRONMENT_FILE.equals(name) || name.startsWith(ENVIRONMENT_FILE + ".") || name.endsWith(".log");
    }
}
