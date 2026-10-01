package dev.softwarefactory.generation.tools;

import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/** Explicit capability catalog shared by conversational and task agents. No mutation or shell tools. */
public final class EngineeringTools {
    private static final String LIST_FILES = "list_files";
    private static final String READ_FILE = "read_file";
    private static final String SEARCH_REPOSITORY = "search_repository";
    private static final String INSPECT_GIT = "inspect_git";
    private static final String FETCH_PAGE = "fetch_page";
    private static final String SEARCH_WEB = "search_web";
    private static final String CURRENT_TIME = "current_time";

    private final RepositoryReader repository;
    private final PublicWebReader web;
    private final WebSearch search;
    private final ToolSession session;
    private final WebAccessPolicy access;

    @FunctionalInterface
    public interface WebSearch {
        String search(String query);
    }

    public EngineeringTools(RepositoryReader repository, PublicWebReader web, WebSearch search, ToolSession session) {
        this(repository, web, search, session, new WebAccessPolicy());
    }

    public EngineeringTools(
            RepositoryReader repository,
            PublicWebReader web,
            WebSearch search,
            ToolSession session,
            WebAccessPolicy access) {
        this.access = access;
        this.repository = repository;
        this.web = web;
        this.search = search;
        this.session = session;
    }

    public List<FunctionTool> declarations() {
        // Java method names; the model sees the snake_case names declared on each method's @Schema.
        return Stream.of(
                        "listFiles",
                        "readFile",
                        "searchRepository",
                        "inspectGit",
                        "fetchPage",
                        "searchWeb",
                        "currentTime")
                .map(method -> FunctionTool.create(this, method))
                .toList();
    }

    @Schema(
            name = LIST_FILES,
            description =
                    "List allowlisted source/documentation paths in this checkout. Empty prefix lists all. Bounded output.")
    public String listFiles(
            @Schema(name = "prefix", description = "Relative path prefix, e.g. factory/src or empty string")
                    String prefix) {
        return session.invoke(LIST_FILES, prefix, () -> repository.list(prefix));
    }

    @Schema(
            name = READ_FILE,
            description =
                    "Read source/documentation with line numbers. Environment files, credentials and symlinks are denied.")
    public String readFile(
            @Schema(name = "path") String path,
            @Schema(name = "start_line") int startLine,
            @Schema(name = "line_count", description = "1..200 lines") int lineCount) {
        return session.invoke(
                READ_FILE, path + ":" + startLine + ":" + lineCount, () -> repository.read(path, startLine, lineCount));
    }

    @Schema(
            name = SEARCH_REPOSITORY,
            description =
                    "Find literal text in allowlisted source/documentation. Returns paths, line numbers and excerpts.")
    public String searchRepository(@Schema(name = "text") String text) {
        return session.invoke(SEARCH_REPOSITORY, text, () -> repository.search(text));
    }

    @Schema(
            name = INSPECT_GIT,
            description =
                    "Inspect Git status, working diff against HEAD, or last five commits for allowlisted source paths. Read-only.")
    public String inspectGit(
            @Schema(name = "operation", description = "Exactly status, diff or log") String operation) {
        return session.invoke(INSPECT_GIT, operation, () -> repository.git(operation));
    }

    @Schema(
            name = FETCH_PAGE,
            description =
                    "Read an exact HTTPS source URL returned by search; query strings are stripped and redirects stay on the same site. No JavaScript, login, cookies or local/private addresses. Cite its source URL.")
    public String fetchPage(@Schema(name = "url") String url) {
        return session.invoke(FETCH_PAGE, url, () -> web.fetch(access.approved(url)));
    }

    @Schema(
            name = SEARCH_WEB,
            description =
                    "Search the public web for current documentation/information, returning a summary and source URLs. Do not send secrets or repository contents in a query. Costs a provider request and up to two searches.")
    public String searchWeb(@Schema(name = "query") String query) {
        return session.invoke(SEARCH_WEB, query, () -> search.search(query));
    }

    @Schema(name = CURRENT_TIME, description = "Read the current UTC time.")
    public String currentTime() {
        return session.invoke(CURRENT_TIME, "", () -> Instant.now().toString());
    }

    public static String help() {
        return "Available automatically in plain-language chat and live task agents:\n\n"
                + "- `search_web` — public web search with sources\n- `fetch_page` — read public HTTPS pages\n"
                + "- `list_files`, `read_file`, `search_repository` — inspect source and documentation\n"
                + "- `inspect_git` — status, diff and recent history\n- `current_time` — UTC time\n\n"
                + "Example: ‘Search official Spring documentation for rate limiting and find the relevant code in this project.’\n\n"
                + "Each answer/task allows up to 8 provider requests and 12 tool calls. Search adds provider charges. "
                + "Code changes, sandbox tests and approvals continue through `/feature`, `/advance` and `/review`.";
    }
}
