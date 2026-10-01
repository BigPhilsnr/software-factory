package dev.softwarefactory.operator.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/** One parsed command line, ready to be carried out. Parsing never touches the database or the workspace. */
@FunctionalInterface
interface CliCommand {
    String USAGE =
            "Usage: start <scenario.json> <fixture|live> | advance <run-id> | review <run-id> | approve <run-id> <reviewed-hash>"
                    + " | reject <run-id> <reviewed-hash> | clarify <run-id> <answer> | revise <run-id> <task-id> [feedback-file] | status <run-id>"
                    + " | metrics <run-id> | verify-audit <run-id> | prune [--days N] [--apply]";

    /**
     * @return a String to print as is, or any other value to print as JSON
     */
    Object run(CliActions actions) throws IOException, InterruptedException;

    static CliCommand parse(String... args) throws UsageException {
        if (args.length < 1) throw new UsageException();
        Parser parser = Parsers.BY_NAME.get(args[0]);
        if (parser == null) throw new UsageException();
        return parser.parse(args);
    }

    /** Turns the arguments of one named command into a command. */
    @FunctionalInterface
    interface Parser {
        CliCommand parse(String... args) throws UsageException;
    }

    /** The command table: each entry checks its own arity and options. */
    final class Parsers {
        static final int DEFAULT_PRUNE_DAYS = 30;

        private static final Map<String, Parser> BY_NAME = Map.ofEntries(
                Map.entry("start", Parsers::start),
                Map.entry("advance", args -> onRun(args, CliActions::advance)),
                Map.entry("approve", args -> decision(args, true)),
                Map.entry("reject", args -> decision(args, false)),
                Map.entry("clarify", Parsers::clarify),
                Map.entry("revise", Parsers::revise),
                Map.entry("status", args -> onRun(args, CliActions::status)),
                Map.entry("review", args -> onRun(args, CliActions::review)),
                Map.entry("metrics", args -> onRun(args, CliActions::metrics)),
                Map.entry("verify-audit", args -> onRun(args, CliActions::verifyAudit)),
                Map.entry("prune", Parsers::prune));

        private Parsers() {}

        @FunctionalInterface
        private interface RunAction {
            Object run(CliActions actions, String runId) throws IOException, InterruptedException;
        }

        private static CliCommand onRun(String[] args, RunAction action) throws UsageException {
            String runId = arity(args, 2)[1];
            return actions -> action.run(actions, runId);
        }

        private static CliCommand start(String... args) throws UsageException {
            Path scenario = Path.of(arity(args, 3)[1]);
            String mode = args[2];
            return actions -> actions.start(scenario, mode);
        }

        private static CliCommand decision(String[] args, boolean accepted) throws UsageException {
            String runId = arity(args, 3)[1];
            String reviewedHash = args[2];
            return actions -> actions.decide(runId, reviewedHash, accepted);
        }

        private static CliCommand clarify(String... args) throws UsageException {
            if (args.length < 3) throw new UsageException();
            String runId = args[1];
            String answer = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            return actions -> actions.clarify(runId, answer);
        }

        private static CliCommand revise(String... args) throws UsageException {
            if (args.length < 3 || args.length > 4) throw new UsageException();
            String runId = args[1];
            String task = args[2];
            Path feedbackFile = args.length == 4 ? Path.of(args[3]) : null;
            return actions -> actions.revise(runId, task, feedbackFile);
        }

        private static CliCommand prune(String... args) throws UsageException {
            int days = DEFAULT_PRUNE_DAYS;
            boolean apply = false;
            int index = 1;
            while (index < args.length) {
                if ("--apply".equals(args[index])) {
                    apply = true;
                    index++;
                } else if ("--days".equals(args[index]) && index + 1 < args.length) {
                    days = days(args[index + 1]);
                    index += 2;
                } else {
                    throw new UsageException();
                }
            }
            int olderThanDays = days;
            boolean delete = apply;
            return actions -> actions.prune(olderThanDays, delete);
        }

        private static int days(String value) throws UsageException {
            int days;
            try {
                days = Integer.parseInt(value);
            } catch (NumberFormatException invalid) {
                throw new UsageException(invalid);
            }
            if (days < 0) throw new UsageException();
            return days;
        }

        private static String[] arity(String[] args, int expected) throws UsageException {
            if (args.length != expected) throw new UsageException();
            return args;
        }
    }
}
