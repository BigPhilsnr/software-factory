package dev.softwarefactory.governance;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Deterministic A2 floor independent of model advice or scenario flags. */
public final class PatchPolicy {
    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(\\S+) b/(\\S+)$");
    private static final String ANY_DIRECTORY = "(?:.*/)?";
    /**
     * Changes to build, dependency, container, security, bootstrap, runtime configuration,
     * schema, test-platform and Git metadata files always need an explicit operator decision.
     */
    private static final List<Pattern> APPROVAL_PATHS = Stream.of(
        ANY_DIRECTORY + "pom\\.xml",
        ANY_DIRECTORY + "(?:docker-)?compose(?:\\.[^/]+)?\\.ya?ml",
        ANY_DIRECTORY + "Dockerfile[^/]*",
        ANY_DIRECTORY + "\\.dockerignore",
        ANY_DIRECTORY + "\\.git[^/]*",
        "\\.github/.*",
        "(?:factory|orchestrator)/.*",
        ANY_DIRECTORY + "bootstrap/.*",
        ANY_DIRECTORY + "[^/]*Security[^/]*\\.java",
        ANY_DIRECTORY + "db/migration/.*",
        ANY_DIRECTORY + "src/main/resources/.*",
        ANY_DIRECTORY + "application[^/]*\\.(?:ya?ml|properties)",
        ANY_DIRECTORY + "src/test/resources/(?:junit-platform\\.properties|META-INF/.*)"
    ).map(Pattern::compile).toList();

    private PatchPolicy() {}

    public static boolean requiresApproval(boolean requested, String patch) {
        if (requested) return true;
        return patch.lines().filter(line -> line.startsWith("diff --git ")).anyMatch(line -> {
            Matcher header = DIFF_HEADER.matcher(line);
            // An unparseable header cannot be classified, so it is never auto-applied.
            if (!header.matches()) return true;
            return requiresApproval(header.group(1)) || requiresApproval(header.group(2));
        });
    }

    static boolean requiresApproval(String path) {
        return APPROVAL_PATHS.stream().anyMatch(pattern -> pattern.matcher(path).matches());
    }
}
