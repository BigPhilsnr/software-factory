package dev.softwarefactory.governance;

import java.util.regex.Pattern;

/** Recounts unified-diff headers without changing paths, offsets, or a single body line. */
public final class PatchHunks {
    private static final Pattern HEADER = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@(.*)$");

    private PatchHunks() {}

    public static String recount(String patch) {
        String[] lines = patch.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            var header = HEADER.matcher(lines[index]);
            if (!header.matches()) continue;
            int end = bodyEnd(lines, index + 1);
            int oldCount = countBody(lines, index + 1, end, '-');
            int newCount = countBody(lines, index + 1, end, '+');
            String corrected = "@@ -" + header.group(1) + "," + oldCount + " +" + header.group(2) + "," + newCount
                    + " @@" + header.group(3);
            // Preserve already-correct headers, including the optional count-of-one spelling.
            if (!countsMatch(lines[index], oldCount, newCount)) lines[index] = corrected;
        }
        return String.join("\n", lines);
    }

    private static int countBody(String[] lines, int start, int end, char sign) {
        int count = 0;
        for (int index = start; index < end; index++) {
            char prefix = lines[index].charAt(0);
            if (prefix == ' ' || prefix == sign) count++;
        }
        return count;
    }

    private static int bodyEnd(String[] lines, int start) {
        int end = start;
        while (end < lines.length && isBody(lines, end)) end++;
        return end;
    }

    private static boolean isBody(String[] lines, int index) {
        String line = lines[index];
        // A traditional diff can start the next file with ---/+++ instead of diff --git.
        if (line.startsWith("--- ") && index + 1 < lines.length && lines[index + 1].startsWith("+++ ")) return false;
        if (line.isEmpty()) return false;
        return " +-".indexOf(line.charAt(0)) >= 0 || "\\ No newline at end of file".equals(line);
    }

    private static boolean countsMatch(String header, int oldCount, int newCount) {
        String[] ranges = header.split(" ");
        return count(ranges[1]) == oldCount && count(ranges[2]) == newCount;
    }

    private static int count(String range) {
        int comma = range.indexOf(',');
        return comma < 0 ? 1 : Integer.parseInt(range.substring(comma + 1));
    }
}
