package dev.softwarefactory.agents.tools;

import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import java.time.Instant;
import java.util.List;

/** Explicit capability catalog shared by conversational and task agents. No mutation or shell tools. */
public final class EngineeringTools {
    @FunctionalInterface public interface WebSearch { String search(String query) throws Exception; }
    private final RepositoryReader repository;
    private final PublicWebReader web;
    private final WebSearch search;
    private final ToolSession session;

    public EngineeringTools(RepositoryReader repository, PublicWebReader web, WebSearch search, ToolSession session) {
        this.repository = repository;
        this.web = web;
        this.search = search;
        this.session = session;
    }

    public List<FunctionTool> declarations() {
        return List.of("list_files", "read_file", "search_repository", "inspect_git", "fetch_page", "search_web", "current_time")
            .stream().map(name -> FunctionTool.create(this, name)).toList();
    }

    @Schema(description = "List allowlisted source/documentation paths in this checkout. Empty prefix lists all. Bounded output.")
    public String list_files(@Schema(name = "prefix", description = "Relative path prefix, e.g. factory/src or empty string") String prefix) throws Exception {
        return session.invoke("list_files", prefix, () -> repository.list(prefix));
    }

    @Schema(description = "Read source/documentation with line numbers. Environment files, credentials and symlinks are denied.")
    public String read_file(@Schema(name = "path") String path, @Schema(name = "start_line") int startLine,
                            @Schema(name = "line_count", description = "1..200 lines") int lineCount) throws Exception {
        return session.invoke("read_file", path + ":" + startLine + ":" + lineCount, () -> repository.read(path, startLine, lineCount));
    }

    @Schema(description = "Find literal text in allowlisted source/documentation. Returns paths, line numbers and excerpts.")
    public String search_repository(@Schema(name = "text") String text) throws Exception {
        return session.invoke("search_repository", text, () -> repository.search(text));
    }

    @Schema(description = "Inspect Git status, working diff against HEAD, or last five commits for allowlisted source paths. Read-only.")
    public String inspect_git(@Schema(name = "operation", description = "Exactly status, diff or log") String operation) throws Exception {
        return session.invoke("inspect_git", operation, () -> repository.git(operation));
    }

    @Schema(description = "Read a public HTTPS page as text. No JavaScript, login, cookies or local/private addresses. Cite its source URL.")
    public String fetch_page(@Schema(name = "url") String url) throws Exception {
        return session.invoke("fetch_page", url, () -> web.fetch(url));
    }

    @Schema(description = "Search the public web for current documentation/information, returning a summary and source URLs. Do not send secrets or repository contents in a query. Costs a provider request and up to two searches.")
    public String search_web(@Schema(name = "query") String query) throws Exception {
        return session.invoke("search_web", query, () -> search.search(query));
    }

    @Schema(description = "Read the current UTC time.")
    public String current_time() throws Exception {
        return session.invoke("current_time", "", () -> Instant.now().toString());
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
