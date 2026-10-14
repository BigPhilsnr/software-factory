package dev.softwarefactory.generation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceNone;
import dev.softwarefactory.generation.tools.ToolSession;
import dev.softwarefactory.platform.GenerationUnavailableException;

/** Provider diagnostics and bounded empty-response recovery, retaining the existing conversation. */
final class ClaudeResponses {
    static final String FINAL_RESPONSE_INSTRUCTION =
            "\nThe tool exploration budget is complete. This request is reserved for your final response. "
                    + "Return the requested artifact using the supplied context and collected evidence. "
                    + "No further tool calls are available. Preserve the requested output format; do not invent observations.";
    private final AnthropicClient client;
    private final ToolSession session;

    ClaudeResponses(AnthropicClient client, ToolSession session) {
        this.client = client;
        this.session = session;
    }

    Message generate(MessageCreateParams.Builder params, String system, boolean hasTools) {
        Message message = requestMessage(params.build());
        if (needsRecovery(message) && session.remainingRequests() > 0) {
            message = recover(params, system, hasTools, message);
        }
        if (isTruncated(message))
            throw new GenerationUnavailableException(
                    "Claude response exceeded the output token limit; incomplete output discarded. Saved draft edits retained; split the remaining work into smaller steps.");
        if (!hasVisibleContent(message))
            throw new GenerationUnavailableException(
                    "Claude returned no usable artifact; bounded recovery exhausted or unavailable. See MODEL_RESPONSE audit metadata");
        return message;
    }

    private Message recover(MessageCreateParams.Builder params, String system, boolean hasTools, Message previous) {
        session.reserveRequest();
        if (isTruncated(previous)) session.recordOutputRecovery();
        else session.recordRecovery();
        boolean allowDraftTools = session.drafting() && session.toolsAllowed();
        if (hasTools && !allowDraftTools)
            params.toolChoice(ToolChoice.ofNone(ToolChoiceNone.builder().build()));
        ClaudeThinking.configure(params, params.build().model().asString(), true);
        params.system(
                system
                        + (allowDraftTools
                                ? "\nThe previous response was unusable. Take one small complete drafting tool step now. Do not emit a large patch."
                                : FINAL_RESPONSE_INSTRUCTION
                                        + "\nThe previous response was empty or exceeded its output limit. Return a smaller complete artifact now."));
        Message result = requestMessage(params.build());
        if (!isTruncated(result)
                && !allowDraftTools
                && result.content().stream().anyMatch(ContentBlock::isToolUse))
            throw new SecurityException("Provider returned a tool call during final-response recovery");
        return result;
    }

    private static boolean isTruncated(Message message) {
        return message.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent();
    }

    private static boolean needsRecovery(Message message) {
        return isTruncated(message)
                || (!hasVisibleContent(message)
                        && message.stopReason()
                                .filter(StopReason.END_TURN::equals)
                                .isPresent());
    }

    private static boolean hasVisibleContent(Message message) {
        return message.content().stream()
                .anyMatch(block -> (!block.isThinking() && !block.isRedactedThinking() && !block.isText())
                        || (block.isText() && !block.asText().text().isBlank()));
    }

    /** Retain only identifiers, stop reason, block kinds and usage in the durable audit. */
    private Message requestMessage(MessageCreateParams params) {
        try (var response = client.messages()
                .withRawResponse()
                .create(
                        params,
                        RequestOptions.builder()
                                .timeout(session.requestTimeout())
                                .build())) {
            Message message = response.parse();
            session.recordResponse("requestId="
                    + safeIdentifier(response.requestId().orElse("unavailable"))
                    + "; messageId=" + safeIdentifier(message.id())
                    + "; stopReason="
                    + safeIdentifier(message.stopReason().map(Object::toString).orElse("missing"))
                    + "; blocks="
                    + message.content().stream().map(ClaudeResponses::blockKind).toList()
                    + "; inputTokens=" + message.usage().inputTokens()
                    + "; outputTokens=" + message.usage().outputTokens());
            return message;
        }
    }

    private static String safeIdentifier(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9_-]", "_");
        return safe.substring(0, Math.min(safe.length(), 160));
    }

    private static String blockKind(ContentBlock block) {
        if (block.isText()) return "text";
        if (block.isToolUse()) return "tool_use";
        if (block.isThinking()) return "thinking";
        if (block.isRedactedThinking()) return "redacted_thinking";
        return "unsupported";
    }
}
