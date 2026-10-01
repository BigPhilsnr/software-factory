package dev.softwarefactory.operator.chat;

import dev.softwarefactory.generation.tools.EngineeringTools;
import dev.softwarefactory.operator.api.FactoryService;
import dev.softwarefactory.platform.LruMap;
import dev.softwarefactory.run.RunMode;
import dev.softwarefactory.run.RunState;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Only literal user commands can change a run; model output never enters this router. Commands that
 * change workflow state are accepted only from requests carrying the operator token (enforced by the
 * HTTP boundary through {@link #changesWorkflowState(String)}).
 */
public final class OperatorCommands {
    /** Prefixes of commands that create, advance or decide runs. */
    private static final List<String> STATE_CHANGING =
            List.of("/feature", "/demo", "/advance", "/approve", "/reject", "/answer", "/changes");

    private static final Pattern AMBIGUOUS_DECISION = Pattern.compile("(?i)(yes|no|approve|approved|reject|continue)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String SELECT = "/select";
    private static final String DEMO = "/demo";
    private static final String FEATURE = "/feature";
    private static final String REJECT = "/reject";

    private final FactoryService factory;
    private final ChatConversation chat;
    private final Map<String, String> selected = LruMap.create(LruMap.DEFAULT_CAPACITY);
    /** Commands that work without a selected run. Bare commands and commands with an argument are distinct. */
    private final Map<String, SessionCommand> sessionCommands =
            Map.of("/tools", (session, argument) -> EngineeringTools.help(), "/runs", this::listRuns);

    private final Map<String, SessionCommand> sessionCommandsWithArgument =
            Map.of(SELECT, this::select, DEMO, this::demo, FEATURE, this::feature);
    /** Commands on the selected run. */
    private final Map<String, RunCommand> runCommands =
            Map.of("/status", this::status, "/advance", this::advance, "/review", this::review);

    private final Map<String, RunCommand> runCommandsWithArgument = Map.of(
            "/approve", this::approve, REJECT, this::reject, "/answer", this::answer, "/changes", this::requestChanges);

    public OperatorCommands(FactoryService factory) {
        this(factory, new ChatConversation(factory.workspaceRoot(), factory.chatRuntime()));
    }

    OperatorCommands(FactoryService factory, ChatConversation chat) {
        this.factory = factory;
        this.chat = chat;
    }

    @FunctionalInterface
    private interface SessionCommand {
        String run(String session, String argument) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    private interface RunCommand {
        String run(String runId, String argument) throws IOException, InterruptedException;
    }

    public static boolean changesWorkflowState(String input) {
        String text = input == null ? "" : input.strip();
        return STATE_CHANGING.stream().anyMatch(command -> text.equals(command) || text.startsWith(command + " "));
    }

    /**
     * @throws IllegalArgumentException for an unknown or incomplete command; nothing was changed
     */
    public String handle(String session, String input) throws IOException, InterruptedException {
        String text = input.strip();
        if (text.isEmpty() || "/help".equals(text) || "help".equalsIgnoreCase(text)) return help();
        if (!text.startsWith("/")) return converse(session, text);
        return dispatch(session, Command.parse(text));
    }

    /** A slash command split at its first space; a bare command and one with an argument are different commands. */
    private record Command(String text, String name, String argument, boolean hasArgument) {
        static Command parse(String text) {
            int space = text.indexOf(' ');
            return space > 0
                    ? new Command(
                            text,
                            text.substring(0, space),
                            text.substring(space).strip(),
                            true)
                    : new Command(text, text, "", false);
        }
    }

    private String dispatch(String session, Command command) throws IOException, InterruptedException {
        SessionCommand sessionCommand =
                (command.hasArgument() ? sessionCommandsWithArgument : sessionCommands).get(command.name());
        if (sessionCommand != null) return sessionCommand.run(session, command.argument());
        String id = selected.get(session);
        if (id == null)
            throw new IllegalArgumentException(
                    "Select a run with /select RUN_ID, use /feature REQUIREMENT, or try /demo bugfix");
        RunCommand runCommand = (command.hasArgument() ? runCommandsWithArgument : runCommands).get(command.name());
        if (runCommand == null) {
            throw new IllegalArgumentException(
                    REJECT.equals(command.text())
                            ? "Use /reject EXACT_HASH from /review"
                            : "Unknown command. Use /help");
        }
        return runCommand.run(id, command.argument());
    }

    /** Plain language never changes a run: it is answered conversationally, or pointed at the exact command. */
    private String converse(String session, String text) throws IOException {
        if (AMBIGUOUS_DECISION.matcher(text).matches()) {
            return "Use `/approve EXACT_HASH`, `/reject EXACT_HASH`, or `/advance` for run actions. Type `/help` for commands.";
        }
        String id = selected.get(session);
        String context = id == null ? "No run selected." : describe(factory.state(id));
        return chat.answer(session, text, context);
    }

    private String listRuns(String ignoredSession, String ignoredArgument) throws IOException {
        StringBuilder result = new StringBuilder("## Factory runs\n\n");
        for (RunState run : factory.runs())
            result.append("- `")
                    .append(run.id)
                    .append("` · ")
                    .append(run.scenario)
                    .append(" · **")
                    .append(run.status)
                    .append("**\n");
        return result.append("\nUse `/select RUN_ID` or [open the operator page](/factory/).")
                .toString();
    }

    private String select(String session, String runId) throws IOException {
        RunState state = factory.state(runId);
        selected.put(session, state.id);
        return describe(state);
    }

    private String demo(String session, String scenario) throws IOException, InterruptedException {
        RunState state = factory.scenario(scenario, RunMode.FIXTURE);
        selected.put(session, state.id);
        factory.advance(state.id);
        return describe(state) + "\n\nFixture demonstration started. Type `/status` to refresh.";
    }

    private String feature(String session, String requirement) throws IOException, InterruptedException {
        RunState state = factory.feature(requirement);
        selected.put(session, state.id);
        return describe(state)
                + "\n\nFeature request saved. Type `/advance` to begin paid live generation, or [open the run](/factory/?run="
                + state.id + ") to review its workflow.";
    }

    private String status(String id, String ignoredArgument) throws IOException {
        return describe(factory.state(id));
    }

    private String advance(String id, String ignoredArgument) throws IOException {
        factory.advance(id);
        return "Run started. Use `/status` to refresh or follow live progress on the [operator page](/factory/?run="
                + id + ").";
    }

    private String approve(String id, String hash) throws IOException, InterruptedException {
        factory.approve(id, hash);
        return "Exact-hash approval recorded. The run is advancing.\n\n" + describe(factory.state(id));
    }

    private String reject(String id, String hash) throws IOException, InterruptedException {
        factory.reject(id, hash);
        return describe(factory.state(id));
    }

    private String answer(String id, String answer) throws IOException, InterruptedException {
        factory.clarify(id, answer);
        return "Answer recorded; the run is advancing.";
    }

    private String requestChanges(String id, String argument) throws IOException, InterruptedException {
        String[] args = WHITESPACE.split(argument, 2);
        if (args.length != 2) throw new IllegalArgumentException("Use /changes TASK_ID your feedback");
        factory.revise(id, args[0], args[1]);
        return "Review feedback recorded; affected tasks are being regenerated.";
    }

    private String review(String id, String ignoredArgument) throws IOException {
        Map<String, Object> detail = factory.detail(id);
        if (!detail.containsKey("review")) return "No approval is pending. " + describe(factory.state(id));
        @SuppressWarnings("unchecked")
        Map<String, Object> review = (Map<String, Object>) detail.get("review");
        return "## Review " + review.get("task") + "\n\nHash: `" + review.get("hash")
                + "`\n\n[View full diff, test evidence and approval controls](/factory/?run=" + id
                + ").\n\nTo approve, send `/approve " + review.get("hash") + "`. To request changes, send `/changes "
                + review.get("task")
                + " YOUR_FEEDBACK`. To reject, send `/reject " + review.get("hash") + "`.";
    }

    private static String describe(RunState state) {
        return "Run `" + state.id + "` · **" + state.status + "** · " + state.mode + "\n\n"
                + "Tasks: " + state.tasks + "\n\nModel calls: " + state.modelCalls + "/" + state.maxModelCalls
                + (state.pendingApprovalTask == null
                        ? ""
                        : "\n\nReview required: **" + state.pendingApprovalTask + "**. Use `/review`.")
                + (state.pendingClarificationTask == null
                        ? ""
                        : "\n\nClarification required. Use `/answer YOUR_ANSWER` or the operator page.")
                + "\n\n[Open this run](/factory/?run=" + state.id + ")";
    }

    private static String help() {
        return "## Software factory\n\nAsk questions about the project or discuss a feature in plain language. Chat answers use the repository and recent conversation; they do not create runs. Use `/feature REQUIREMENT` to save a feature request, then `/advance` to start it. Chat answers use paid model calls, separately from run budgets.\n\n"
                + "- `/demo bugfix` — start a fixture demonstration without model charges\n"
                + "- `/tools` — show web browsing and engineering tools\n"
                + "- `/runs` and `/select RUN_ID` — find an existing run\n"
                + "- `/status` — refresh selected run\n"
                + "- `/review` — show pending approval details\n"
                + "- `/approve EXACT_HASH` — approve the reviewed proposal and resume\n"
                + "- `/changes TASK_ID feedback` — request revision\n"
                + "- `/answer answer` — answer a clarification\n"
                + "- `/reject EXACT_HASH` — reject the reviewed proposal\n\n"
                + "Commands that create, advance or decide runs require the operator token (sent by the operator page or the "
                + "`X-Factory-Token` header); without it they are refused.\n\n"
                + "[Open the operator page for live progress, artifact inspection and approval buttons](/factory/).";
    }
}
