package dev.softwarefactory.agents;

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
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.genai.types.FunctionCall;
import com.google.adk.JsonBaseModel;
import dev.softwarefactory.agents.tools.ToolSession;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;

/** Bridges ADK function calls while retaining signed thinking privately for provider round trips. */
final class ThinkingAwareClaude extends Claude {
    private final AnthropicClient client;
    private final String modelName;
    private final ToolSession session;
    private final Map<String, MessageParam> toolTurns = new HashMap<>();

    ThinkingAwareClaude(String modelName, AnthropicClient client) {
        this(modelName, client, new ToolSession(() -> {}, (event, detail) -> {}));
    }

    ThinkingAwareClaude(String modelName, AnthropicClient client, ToolSession session) {
        super(modelName, client);
        this.client = client;
        this.modelName = modelName;
        this.session = session;
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        if (stream) throw new IllegalArgumentException("Factory Claude runtime uses non-streaming provider calls");
        MessageCreateParams.Builder params = MessageCreateParams.builder()
            .model(request.model().orElse(modelName))
            .system(String.join("\n", request.getSystemInstructions()))
            .maxTokens(32768);
        for (Content content : request.contents()) params.addMessage(toMessage(content));
        request.config().flatMap(config -> config.tools()).orElse(List.of()).forEach(group -> {
            group.functionDeclarations().orElse(List.of()).forEach(declaration -> {
                Map<String, Object> properties = new HashMap<>();
                declaration.parameters().flatMap(schema -> schema.properties()).orElse(Map.of()).forEach((key, schema) -> {
                    Map<String, Object> value = JsonBaseModel.getMapper().convertValue(schema, new TypeReference<>() {});
                    if (value.get("type") instanceof String type) value.put("type", type.toLowerCase(Locale.ROOT));
                    properties.put(key, value);
                });
                params.addTool(Tool.builder().name(declaration.name().orElseThrow())
                    .description(declaration.description().orElse(""))
                    .inputSchema(Tool.InputSchema.builder().properties(JsonValue.from(properties))
                        .required(declaration.parameters().flatMap(schema -> schema.required()).orElse(List.of())).build()).build());
            });
        });
        if (!request.tools().isEmpty()) {
            params.toolChoice(ToolChoice.ofAuto(ToolChoiceAuto.builder().disableParallelToolUse(true).build()));
        }
        session.reserveRequest();
        Message message = client.messages().create(params.build());
        if (message.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
            throw new IllegalStateException("Claude response exceeded the output token limit");
        }
        List<Part> parts = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (block.isText()) parts.add(Part.fromText(block.asText().text()));
            else if (block.isToolUse()) {
                var call = block.asToolUse();
                toolTurns.put(call.id(), message.toParam());
                parts.add(Part.builder().functionCall(FunctionCall.builder().id(call.id()).name(call.name())
                    .args(call._input().convert(new TypeReference<Map<String, Object>>() {})).build()).build());
            } else if (!block.isThinking() && !block.isRedactedThinking()) {
                throw new IllegalStateException("Unsupported provider content block");
            }
        }
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

    private MessageParam toMessage(Content content) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (Part part : content.parts().orElse(List.of())) {
            if (part.functionCall().isPresent()) {
                // ADK stores only the public function call; replay the complete signed provider turn.
                MessageParam original = toolTurns.get(part.functionCall().orElseThrow().id().orElseThrow());
                if (original == null) throw new IllegalStateException("Missing original provider tool turn");
                return original;
            }
            if (part.text().isPresent()) blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(part.text().orElseThrow()).build()));
            else if (part.functionResponse().isPresent()) {
                var response = part.functionResponse().orElseThrow();
                try {
                    blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(response.id().orElseThrow())
                        .content(JsonBaseModel.getMapper().writeValueAsString(response.response().orElse(Map.of()))).build()));
                } catch (java.io.IOException failure) { throw new IllegalStateException("Could not serialize tool result", failure); }
            } else throw new IllegalArgumentException("Unsupported conversation part");
        }
        return MessageParam.builder().role(content.role().orElse("user").equals("model") ? MessageParam.Role.ASSISTANT : MessageParam.Role.USER)
            .contentOfBlockParams(blocks).build();
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
