package com.example.factory.infra;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.google.adk.models.Claude;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Bridges Claude's default thinking blocks to the text-only ADK workflow used by this factory. */
final class ThinkingAwareClaude extends Claude {
    private final AnthropicClient client;
    private final String modelName;

    ThinkingAwareClaude(String modelName, AnthropicClient client) {
        super(modelName, client);
        this.client = client;
        this.modelName = modelName;
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        if (stream || !request.tools().isEmpty()) {
            throw new IllegalArgumentException("Factory Claude runtime supports text-only, non-streaming calls");
        }
        MessageCreateParams.Builder params = MessageCreateParams.builder()
            .model(request.model().orElse(modelName))
            .system(String.join("\n", request.getSystemInstructions()))
            .maxTokens(32768);
        for (Content content : request.contents()) {
            StringBuilder text = new StringBuilder();
            for (Part part : content.parts().orElse(List.of())) {
                if (part.text().isEmpty()) throw new IllegalArgumentException("Factory Claude runtime requires text parts");
                text.append(part.text().orElseThrow());
            }
            if (content.role().orElse("user").equals("model")) params.addAssistantMessage(text.toString());
            else params.addUserMessage(text.toString());
        }
        Message message = client.messages().create(params.build());
        if (message.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
            throw new IllegalStateException("Claude response exceeded the output token limit");
        }
        List<Part> parts = visibleText(message.content()).stream().map(Part::fromText).collect(Collectors.toList());
        if (parts.isEmpty()) throw new IllegalStateException("Claude returned no visible text");
        LlmResponse.Builder response = LlmResponse.builder()
            .content(Content.builder().role("model").parts(parts).build());
        response.usageMetadata(GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount((int) message.usage().inputTokens())
            .candidatesTokenCount((int) message.usage().outputTokens())
            .totalTokenCount((int) (message.usage().inputTokens() + message.usage().outputTokens()))
            .build());
        return Flowable.just(response.build());
    }

    static List<String> visibleText(List<ContentBlock> blocks) {
        List<String> text = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block.isText()) text.add(block.asText().text());
            else if (!block.isThinking() && !block.isRedactedThinking()) {
                throw new IllegalStateException("Claude returned an unsupported content block");
            }
        }
        return text;
    }
}
