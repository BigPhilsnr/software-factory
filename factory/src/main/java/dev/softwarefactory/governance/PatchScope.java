package dev.softwarefactory.governance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Worker authority over paths: a generated patch may only edit plain files inside its task's write scope. */
public final class PatchScope {
    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern LINK_OR_SUBMODULE =
            Pattern.compile("(?:new file mode|old mode|new mode|deleted file mode|index [^ ]+) (?:120000|160000)");
    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9_./-]+");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,(\\d+))? \\+\\d+(?:,(\\d+))? @@.*$");
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
        List<String> lines = patch.lines().toList();
        requireAccurateHunkCounts(lines);
        List<String> changed = new ArrayList<>();
        for (String line : lines) {
            String path = changedPath(line);
            if (path == null) continue;
            if (!isWritable(path, allowed)) throw new PolicyViolationException("Patch outside approved scope: " + path);
            changed.add(path);
        }
        if (changed.isEmpty()) throw new IllegalArgumentException("Patch has no recognized file changes");
        return changed;
    }

    /**
     * A hunk header declares how many old- and new-side lines its body holds. A model can get that
     * count wrong without producing anything else recognizably malformed; {@code git apply} then
     * silently stops reading the hunk at the declared count and drops the remaining lines, so a file
     * can lose its closing braces with no error until a much later compile step. Counting the body
     * ourselves and rejecting a mismatch turns that into an immediate, specific, retryable diagnostic.
     */
    private static void requireAccurateHunkCounts(List<String> lines) {
        int index = 0;
        while (index < lines.size()) {
            Matcher header = HUNK_HEADER.matcher(lines.get(index));
            index = header.matches() ? requireMatchingHunk(lines, index, header) : index + 1;
        }
    }

    /** @return the index of the first line after this hunk's body */
    private static int requireMatchingHunk(List<String> lines, int headerIndex, Matcher header) {
        int declaredOld = count(header.group(1));
        int declaredNew = count(header.group(2));
        HunkBody actual = countHunkBody(lines, headerIndex + 1);
        if (actual.oldLines() != declaredOld || actual.newLines() != declaredNew) {
            throw new IllegalArgumentException("Hunk header declares -%d,+%d lines but the body has -%d,+%d"
                    .formatted(declaredOld, declaredNew, actual.oldLines(), actual.newLines()));
        }
        return actual.nextIndex();
    }

    private record HunkBody(int oldLines, int newLines, int nextIndex) {}

    /** Counts a hunk's old- and new-side lines from its first body line up to its first non-body line. */
    private static HunkBody countHunkBody(List<String> lines, int start) {
        int oldLines = 0;
        int newLines = 0;
        int index = start;
        while (index < lines.size() && isHunkBodyLine(lines.get(index))) {
            String line = lines.get(index);
            if (!line.startsWith("\\")) {
                if (line.isEmpty() || line.charAt(0) != '+') oldLines++;
                if (line.isEmpty() || line.charAt(0) != '-') newLines++;
            }
            index++;
        }
        return new HunkBody(oldLines, newLines, index);
    }

    private static int count(String declared) {
        return declared == null ? 1 : Integer.parseInt(declared);
    }

    /** A hunk body line: context, addition, removal or the no-newline marker. Anything else ends it. */
    private static boolean isHunkBodyLine(String line) {
        return line.isEmpty() || " +-\\".indexOf(line.charAt(0)) >= 0;
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
