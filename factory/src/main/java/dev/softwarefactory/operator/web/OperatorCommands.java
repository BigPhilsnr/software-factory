package dev.softwarefactory.operator.web;

import dev.softwarefactory.workflow.RunState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Only literal user commands can approve a run; model output never enters this router. */
final class OperatorCommands {
    private final FactoryService factory;
    private final ChatConversation chat;
    private final Map<String, String> selected = new ConcurrentHashMap<>();
    OperatorCommands(FactoryService factory) { this(factory, factory.conversation()); }
    OperatorCommands(FactoryService factory, ChatConversation chat) { this.factory = factory; this.chat = chat; }

    String handle(String session, String input) throws Exception {
        String text = input.strip();
        if (text.isEmpty() || text.equals("/help") || text.equalsIgnoreCase("help")) return help();
        if (text.equals("/tools")) return dev.softwarefactory.agents.tools.EngineeringTools.help();
        if (text.equals("/runs")) {
            StringBuilder result = new StringBuilder("## Factory runs\n\n");
            for (RunState run : factory.runs()) result.append("- `").append(run.id).append("` · ").append(run.scenario).append(" · **").append(run.status).append("**\n");
            return result.append("\nUse `/select RUN_ID` or [open the operator page](/factory/).").toString();
        }
        if (text.startsWith("/select ")) {
            RunState state = factory.state(text.substring(8).strip());
            selected.put(session, state.id);
            return describe(state);
        }
        if (text.startsWith("/demo ")) {
            RunState state = factory.scenario(text.substring(6).strip(), "fixture");
            selected.put(session, state.id);
            factory.advance(state.id);
            return describe(state) + "\n\nFixture demonstration started. Type `/status` to refresh.";
        }
        if (!text.startsWith("/")) {
            if (text.matches("(?i)(yes|no|approve|approved|reject|continue)")) {
                return "Use `/approve EXACT_HASH`, `/reject`, or `/advance` for run actions. Type `/help` for commands.";
            }
            String id = selected.get(session);
            String context = id == null ? "No run selected." : describe(factory.state(id));
            return chat.answer(session, text, context);
        }
        if (text.startsWith("/feature ")) {
            RunState state = factory.feature(text.substring(9));
            selected.put(session, state.id);
            return describe(state) + "\n\nFeature request saved. Type `/advance` to begin paid live generation, or [open the run](/factory/?run=" + state.id + ") to review its workflow.";
        }
        String id = selected.get(session);
        if (id == null) throw new IllegalArgumentException("Select a run with /select RUN_ID, use /feature REQUIREMENT, or try /demo bugfix");
        if (text.equals("/status")) return describe(factory.state(id));
        if (text.equals("/advance")) { factory.advance(id); return "Run started. Use `/status` to refresh or follow live progress on the [operator page](/factory/?run=" + id + ")."; }
        if (text.equals("/review")) {
            Map<String, Object> detail = factory.detail(id);
            if (!detail.containsKey("review")) return "No approval is pending. " + describe(factory.state(id));
            @SuppressWarnings("unchecked") Map<String, Object> review = (Map<String, Object>) detail.get("review");
            return "## Review " + review.get("task") + "\n\nHash: `" + review.get("hash") + "`\n\n[View full diff, test evidence and approval controls](/factory/?run=" + id + ").\n\nTo approve, send `/approve " + review.get("hash") + "`. To request changes, send `/changes " + review.get("task") + " YOUR_FEEDBACK`. To reject, send `/reject`.";
        }
        if (text.startsWith("/approve ")) { factory.approve(id, text.substring(9).strip()); return "Exact-hash approval recorded. The run is advancing.\n\n" + describe(factory.state(id)); }
        if (text.equals("/reject")) { factory.reject(id, factory.state(id).pendingApprovalHash); return describe(factory.state(id)); }
        if (text.startsWith("/answer ")) { factory.clarify(id, text.substring(8)); return "Answer recorded; the run is advancing."; }
        if (text.startsWith("/changes ")) {
            String[] args = text.substring(9).split("\\s+", 2);
            if (args.length != 2) throw new IllegalArgumentException("Use /changes TASK_ID your feedback");
            factory.revise(id, args[0], args[1]);
            return "Review feedback recorded; affected tasks are being regenerated.";
        }
        throw new IllegalArgumentException("Unknown command. Use /help");
    }

    private String describe(RunState state) {
        return "Run `" + state.id + "` · **" + state.status + "** · " + state.mode + "\n\n"
            + "Tasks: " + state.tasks + "\n\nModel calls: " + state.modelCalls + "/" + state.maxModelCalls
            + (state.pendingApprovalTask == null ? "" : "\n\nReview required: **" + state.pendingApprovalTask + "**. Use `/review`.")
            + (state.pendingClarificationTask == null ? "" : "\n\nClarification required. Use `/answer YOUR_ANSWER` or the operator page.")
            + "\n\n[Open this run](/factory/?run=" + state.id + ")";
    }

    private String help() {
        return "## Software factory\n\nAsk questions about the project or discuss a feature in plain language. Chat answers use the repository and recent conversation; they do not create runs. Use `/feature REQUIREMENT` to save a feature request, then `/advance` to start it. Chat answers use paid model calls, separately from run budgets.\n\n"
            + "- `/demo bugfix` — start a fixture demonstration without model charges\n"
            + "- `/tools` — show web browsing and engineering tools\n"
            + "- `/runs` and `/select RUN_ID` — find an existing run\n"
            + "- `/status` — refresh selected run\n"
            + "- `/review` — show pending approval details\n"
            + "- `/approve EXACT_HASH` — approve the reviewed proposal and resume\n"
            + "- `/changes TASK_ID feedback` — request revision\n"
            + "- `/answer answer` — answer a clarification\n"
            + "- `/reject` — reject pending approval\n\n"
            + "[Open the operator page for live progress, artifact inspection and approval buttons](/factory/).";
    }
}
