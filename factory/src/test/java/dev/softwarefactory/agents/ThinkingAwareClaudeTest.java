package dev.softwarefactory.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.RedactedThinkingBlock;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ToolUseBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThinkingAwareClaudeTest {
    @Test
    void excludesPrivateThinkingWhilePreservingVisibleText() {
        List<ContentBlock> blocks = List.of(
            ContentBlock.ofThinking(ThinkingBlock.builder().thinking("private").signature("sig").build()),
            ContentBlock.ofText(TextBlock.builder().text("first").citations(List.of()).build()),
            ContentBlock.ofRedactedThinking(RedactedThinkingBlock.builder().data("hidden").build()),
            ContentBlock.ofText(TextBlock.builder().text("second").citations(List.of()).build())
        );

        assertEquals(List.of("first", "second"), ThinkingAwareClaude.visibleText(blocks));
    }

    @Test
    void rejectsUnexpectedBlocksInsteadOfSilentlyDroppingThem() {
        ContentBlock unexpected = ContentBlock.ofToolUse(ToolUseBlock.builder()
            .id("tool-id").name("unexpected").input(com.anthropic.core.JsonValue.from("{}"))
            .caller(DirectCaller.builder().build())
            .build());
        assertThrows(IllegalStateException.class, () -> ThinkingAwareClaude.visibleText(List.of(unexpected)));
    }
}
