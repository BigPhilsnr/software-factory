package dev.softwarefactory.operator.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.softwarefactory.operator.api.FactoryService;
import dev.softwarefactory.operator.api.NotFoundException;
import dev.softwarefactory.operator.api.ServiceUnavailableException;
import dev.softwarefactory.platform.InfrastructureException;
import dev.softwarefactory.platform.WorkflowConflictException;
import dev.softwarefactory.run.RunState;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FactoryAgentTest {
    private static final String ID = "00000000-0000-0000-0000-0000000000ff";

    @TempDir
    Path root;

    private final FactoryService factory = mock(FactoryService.class);
    private FactoryAgent agent;

    @BeforeEach
    void setup() {
        when(factory.workspaceRoot()).thenReturn(root);
        when(factory.chatRuntime()).thenReturn((role, prompt) -> "Conversational answer");
        agent = new FactoryAgent(factory);
    }

    @Test
    void theAgentIsRegisteredUnderItsChatName() {
        assertEquals("software_factory", agent.name());
        assertTrue(agent.description().contains("/help"));
    }

    @Test
    void commandsAndQuestionsAreAnswered() throws Exception {
        when(factory.runs()).thenReturn(List.of(new RunState(ID, "demo", "hash")));
        assertTrue(agent.answer("session", "/runs").contains("`" + ID + "` · demo · **CREATED**"));
        assertEquals("Conversational answer", agent.answer("session", "How do approvals work?"));
        assertTrue(agent.answer("session", "").startsWith("## Software factory"));
    }

    @Test
    void refusedActionsExplainWhyAndUnexpectedFailuresRevealOnlyTheirType() throws Exception {
        assertEquals(
                "Action not performed: Select a run with /select RUN_ID, use /feature REQUIREMENT, or try /demo bugfix",
                agent.answer("session", "/advance"));

        when(factory.state(ID)).thenReturn(new RunState(ID, "demo", "hash"));
        agent.answer("session", "/select " + ID);
        doThrow(new WorkflowConflictException("This run is already active"))
                .when(factory)
                .advance(ID);
        assertEquals("Action not performed: This run is already active", agent.answer("session", "/advance"));
        doThrow(new ServiceUnavailableException("Two runs are active"))
                .when(factory)
                .advance(ID);
        assertEquals("Action not performed: Two runs are active", agent.answer("session", "/advance"));
        doThrow(new NotFoundException("Scenario specification not found"))
                .when(factory)
                .detail(ID);
        assertEquals("Action not performed: Scenario specification not found", agent.answer("session", "/review"));

        doThrow(new InfrastructureException("password=hunter2 rejected"))
                .when(factory)
                .advance(ID);
        String failed = agent.answer("session", "/advance");
        assertTrue(failed.startsWith("The request failed."));
        assertTrue(failed.endsWith("Error: InfrastructureException"));
        doThrow(new InterruptedException("shutdown")).when(factory).approve(any(), any());
        try {
            assertTrue(agent.answer("session", "/approve abc").endsWith("Error: InterruptedException"));
            assertTrue(Thread.currentThread().isInterrupted(), "The interrupt is preserved for the caller");
        } finally {
            Thread.interrupted();
        }
    }
}
