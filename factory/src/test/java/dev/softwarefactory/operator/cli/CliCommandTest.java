package dev.softwarefactory.operator.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.softwarefactory.run.RunState;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CliCommandTest {
    private static final String ID = "00000000-0000-0000-0000-0000000000dd";

    private final CliActions actions = mock(CliActions.class);

    private Object run(String... args) throws Exception {
        return CliCommand.parse(args).run(actions);
    }

    @Test
    void transitionsAreParsedIntoEngineActions() throws Exception {
        RunState state = new RunState(ID, "demo", "hash");
        when(actions.start(Path.of("scenarios/bugfix/scenario.json"), "fixture"))
                .thenReturn(state);
        assertEquals(state, run("start", "scenarios/bugfix/scenario.json", "fixture"));
        run("advance", ID);
        run("approve", ID, "abc");
        run("reject", ID, "abc");
        run("clarify", ID, "one", "instance,", "immutable", "links");
        run("revise", ID, "apply");
        run("revise", ID, "apply", "feedback.txt");
        verify(actions).advance(ID);
        verify(actions).decide(ID, "abc", true);
        verify(actions).decide(ID, "abc", false);
        verify(actions).clarify(ID, "one instance, immutable links");
        verify(actions).revise(ID, "apply", null);
        verify(actions).revise(ID, "apply", Path.of("feedback.txt"));
    }

    @Test
    void inspectionCommandsAreParsedIntoReadOnlyActions() throws Exception {
        when(actions.verifyAudit(ID)).thenReturn("AUDIT_VALID");
        when(actions.review(ID)).thenReturn(Map.of("reviewedHash", "abc"));
        assertEquals("AUDIT_VALID", run("verify-audit", ID));
        assertEquals(Map.of("reviewedHash", "abc"), run("review", ID));
        run("status", ID);
        run("metrics", ID);
        verify(actions).status(ID);
        verify(actions).metrics(ID);
    }

    @Test
    void pruneListsByDefaultAndDeletesOnlyWhenAskedTo() throws Exception {
        run("prune");
        run("prune", "--days", "7");
        run("prune", "--apply", "--days", "0");
        verify(actions).prune(30, false);
        verify(actions).prune(7, false);
        verify(actions).prune(0, true);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "unknown",
                "start scenario.json",
                "start scenario.json fixture extra",
                "advance",
                "advance a b",
                "approve id",
                "reject id",
                "clarify id",
                "revise id",
                "revise id task file extra",
                "status",
                "review",
                "metrics",
                "verify-audit",
                "prune --days",
                "prune --days soon",
                "prune --days -1",
                "prune --force"
            })
    void malformedCommandLinesAreUsageErrorsBeforeAnythingRuns(String line) {
        String[] args = line.isEmpty() ? new String[0] : line.split(" ");
        assertThrows(UsageException.class, () -> CliCommand.parse(args));
        verifyNoMoreInteractions(actions);
    }

    @Test
    void textResultsPrintAsIsAndEverythingElseAsJson() throws Exception {
        assertEquals("AUDIT_VALID", FactoryCli.render("AUDIT_VALID"));
        String json = FactoryCli.render(new RunState(ID, "demo", "hash"));
        assertTrue(json.contains("\"id\" : \"" + ID + "\""));
        assertTrue(json.contains("\"status\" : \"CREATED\""));
        assertTrue(CliCommand.USAGE.startsWith("Usage: start <scenario.json> <fixture|live>"));
    }
}
