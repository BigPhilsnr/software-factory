package dev.softwarefactory.generation;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigDisabled;

/** Controls supported adaptive models explicitly; unknown model IDs retain provider defaults. */
final class ClaudeThinking {
    private ClaudeThinking() {}

    static void configure(MessageCreateParams.Builder params, String model, boolean recovery) {
        if (!adaptive(model)) return;
        params.outputConfig(OutputConfig.builder()
                .effort(recovery ? OutputConfig.Effort.LOW : OutputConfig.Effort.MEDIUM)
                .build());
        // Sonnet 5 supports disabled thinking; Sonnet 5.5 and other families have different rules.
        if (recovery && ("claude-sonnet-5".equals(model) || model.startsWith("claude-sonnet-5-202")))
            params.thinking(ThinkingConfigDisabled.builder().build());
    }

    private static boolean adaptive(String model) {
        return model.startsWith("claude-sonnet-5")
                || model.startsWith("claude-opus-5")
                || model.startsWith("claude-sonnet-4-6")
                || model.startsWith("claude-opus-4-6")
                || model.startsWith("claude-opus-4-7")
                || model.startsWith("claude-opus-4-8");
    }
}
