package dev.softwarefactory.generation.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.adk.tools.FunctionTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineeringToolsTest {
    @TempDir
    Path root;

    @Test
    void theModelFacingToolNamesAreAStableContract() {
        try (var web = new PublicWebReader();
                var session = new ToolSession(() -> {}, (event, detail) -> {})) {
            var tools = new EngineeringTools(new RepositoryReader(root), web, query -> "results", session);
            assertEquals(
                    List.of(
                            "list_files",
                            "read_file",
                            "search_repository",
                            "inspect_git",
                            "fetch_page",
                            "search_web",
                            "current_time"),
                    tools.declarations().stream().map(FunctionTool::name).toList());
            for (String name : List.of("search_web", "fetch_page", "list_files", "read_file", "inspect_git")) {
                assertTrue(EngineeringTools.help().contains("`" + name + "`"), name);
            }
        }
    }

    @Test
    void everyToolCallIsAuditedByHashAndFailuresBecomeSafeResults() throws Exception {
        Files.writeString(root.resolve("README.md"), "first line\nsecond line\n");
        List<String> events = new ArrayList<>();
        try (var web = new PublicWebReader();
                var session = new ToolSession(() -> {}, (event, detail) -> events.add(event + " " + detail))) {
            var tools = new EngineeringTools(new RepositoryReader(root), web, query -> "UNTRUSTED results", session);
            assertTrue(tools.listFiles("").contains("README.md"));
            assertTrue(tools.readFile("README.md", 2, 1).contains("2: second line"));
            assertTrue(tools.searchRepository("second").contains("README.md:2:second line"));
            assertTrue(tools.searchWeb("spring rate limiting").contains("UNTRUSTED results"));
            assertTrue(tools.currentTime().contains("Tool result: current_time"));
            String denied = tools.fetchPage("https://example.com/never-returned-by-search");
            assertTrue(denied.contains("Tool unavailable or request denied (SecurityException)"));
            String noGit = tools.inspectGit("push");
            assertTrue(noGit.contains("Tool unavailable or request denied (IllegalArgumentException)"));
            assertEquals(
                    "list_files OK, read_file OK, search_repository OK, search_web OK, current_time OK, "
                            + "fetch_page ERROR, inspect_git ERROR",
                    session.summary());
        }
        assertEquals(14, events.size(), "One start and one finish event per call");
        assertTrue(events.stream().noneMatch(event -> event.contains("second line") || event.contains("spring")));
        assertTrue(events.getFirst().startsWith("TOOL_STARTED list_files:inputSha256="));
    }
}
