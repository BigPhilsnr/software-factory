package dev.softwarefactory.operator.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.operator.api.FactoryService;
import dev.softwarefactory.run.RunState;
import dev.softwarefactory.run.RunStatus;
import dev.softwarefactory.run.TaskStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatRunContextTest {
    @TempDir
    Path root;

    @Test
    void newChatSeesCompletedFeatureFromOperatorUiWithoutSelection() throws Exception {
        var factory = mock(FactoryService.class);
        var run = completed(factory, "one", "DELETE /api/urls/{code} removes a short link");
        when(factory.runs()).thenReturn(List.of(run));
        var prompts = new ArrayList<String>();
        var commands = new OperatorCommands(factory, new ChatConversation(root, (role, prompt) -> {
            prompts.add(prompt);
            return "answer";
        }));
        commands.handle("new-session", "Describe the new feature");
        assertTrue(prompts.getFirst().contains("DELETE /api/urls/{code}"));
        assertTrue(prompts.getFirst().contains("status=COMPLETED"));
        assertTrue(prompts.getFirst().contains("does not mean merged"));
        assertTrue(prompts.getFirst().contains("evidence/one/documentation-v1.txt"));
        verify(factory, never()).advance(anyString());
        verify(factory, never()).feature(anyString());
    }

    @Test
    void explicitSelectionWinsAndEvidenceRefreshesOnEveryQuestion() throws Exception {
        var factory = mock(FactoryService.class);
        var run = completed(factory, "selected", "first version");
        var context = new ChatRunContext(factory);
        assertTrue(context.read("selected").contains("first version"));
        run.artifactVersions.put("documentation", 2);
        run.artifactHashes.put("documentation", Hashes.sha256("updated behavior"));
        when(factory.artifact("selected", "documentation-v2.txt")).thenReturn("updated behavior");
        assertTrue(context.read("selected").contains("updated behavior"));
        assertFalse(context.read("selected").contains("first version"));
        verify(factory, never()).runs();
    }

    @Test
    void modifiedOrUnverifiedEvidenceIsNotPresentedAsFeatureBehavior() throws Exception {
        var factory = mock(FactoryService.class);
        completed(factory, "one", "original");
        when(factory.artifact("one", "documentation-v1.txt")).thenReturn("TAMPERED_SENTINEL");
        var context = new ChatRunContext(factory);
        assertFalse(context.read("one").contains("TAMPERED_SENTINEL"));
        when(factory.detail("one")).thenReturn(Map.of("requirement", "Delete link", "auditValid", false));
        assertTrue(context.read("one").contains("Audit could not be verified"));
    }

    private static RunState completed(FactoryService factory, String id, String documentation) throws Exception {
        var run = new RunState(id, "feature-request", "hash");
        run.status = RunStatus.COMPLETED;
        run.candidatePath = ".runs/" + id;
        run.tasks.put("documentation", TaskStatus.DONE);
        run.artifactVersions.put("documentation", 1);
        run.artifactHashes.put("documentation", Hashes.sha256(documentation));
        when(factory.state(id)).thenReturn(run);
        when(factory.detail(id)).thenReturn(Map.of("requirement", "Delete link", "auditValid", true));
        when(factory.artifact(id, "documentation-v1.txt")).thenReturn(documentation);
        return run;
    }
}
