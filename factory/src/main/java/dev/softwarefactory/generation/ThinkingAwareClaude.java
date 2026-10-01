package dev.softwarefactory.generation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.JsonBaseModel;
import com.google.adk.models.Claude;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import dev.softwarefactory.generation.tools.ToolSession;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bridges ADK function calls while retaining signed thinking privately for provider round trips. */
final class ThinkingAwareClaude extends Claude {
    /** Large enough for a complete patch artifact; longer output is rejected rather than truncated. */
    private static final int MAX_OUTPUT_TOKENS = 32_768;

    private static final String MODEL_ROLE = "model";
    private static final String SCHEMA_TYPE = "type";
    private static final String FINAL_RESPONSE_INSTRUCTION =
            "\nThe tool exploration budget is complete. This request is reserved for your final response. "
                    + "Return the requested artifact using the supplied context and collected evidence. "
                    + "No further tool calls are available. Preserve the requested output format; do not invent observations.";

    private final AnthropicClient client;
    private final String modelName;
    private final ToolSession session;
    private final Map<String, MessageParam> toolTurns = new HashMap<>();

    ThinkingAwareClaude(String modelName, AnthropicClient client, ToolSession session) {
        super(modelName, client);
        this.client = client;
        this.modelName = modelName;
        this.session = session;
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        if (stream) throw new IllegalArgumentException("Factory Claude runtime uses non-streaming provider calls");
        String system = String.join("\n", request.getSystemInstructions());
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(request.model().orElse(modelName))
                .system(system)
                .maxTokens(MAX_OUTPUT_TOKENS);
        for (Content content : request.contents()) params.addMessage(toMessage(content));
        declareTools(request, params);
        session.reserveRequest();
        boolean toolsAllowed = session.toolsAllowed();
        if (!request.tools().isEmpty()) chooseTools(params, system, toolsAllowed);
        Message message = client.messages()
                .create(
                        params.build(),
                        RequestOptions.builder()
                                .timeout(session.requestTimeout())
                                .build());
        if (message.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
            throw new IllegalStateException("Claude response exceeded the output token limit");
        }
        List<Part> parts = visibleParts(message, toolsAllowed);
        return Flowable.just(LlmResponse.builder()
                .content(Content.builder().role(MODEL_ROLE).parts(parts).build())
                .usageMetadata(GenerateContentResponseUsageMetadata.builder()
                        .promptTokenCount((int) message.usage().inputTokens())
                        .candidatesTokenCount((int) message.usage().outputTokens())
                        .totalTokenCount((int)
                                (message.usage().inputTokens() + message.usage().outputTokens()))
                        .build())
                .build());
    }

    /** Text and tool calls of a response; thinking blocks stay private to provider round trips. */
    private List<Part> visibleParts(Message message, boolean toolsAllowed) {
        List<Part> parts = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            Part part = toPart(block, message, toolsAllowed);
            if (part != null) parts.add(part);
        }
        if (parts.isEmpty()) throw new IllegalStateException("Claude returned no visible text");
        return parts;
    }

    /** While budget remains the model may call one tool at a time; the last request must be the final answer. */
    private void chooseTools(MessageCreateParams.Builder params, String system, boolean toolsAllowed) {
        if (toolsAllowed) {
            params.toolChoice(ToolChoice.ofAuto(
                    ToolChoiceAuto.builder().disableParallelToolUse(true).build()));
        } else {
            params.toolChoice(ToolChoice.ofNone(ToolChoiceNone.builder().build()));
            params.system(system + FINAL_RESPONSE_INSTRUCTION);
            session.recordFinalization();
        }
    }

    /** Translates ADK function declarations into provider tool definitions (lower-case JSON schema types). */
    private static void declareTools(LlmRequest request, MessageCreateParams.Builder params) {
        request.config().flatMap(config -> config.tools()).orElse(List.of()).stream()
                .flatMap(group -> group.functionDeclarations().orElse(List.of()).stream())
                .forEach(declaration -> {
                    Map<String, Object> properties = new HashMap<>();
                    declaration
                            .parameters()
                            .flatMap(schema -> schema.properties())
                            .orElse(Map.of())
                            .forEach((key, schema) -> properties.put(key, providerSchema(schema)));
                    params.addTool(Tool.builder()
                            .name(declaration.name().orElseThrow())
                            .description(declaration.description().orElse(""))
                            .inputSchema(Tool.InputSchema.builder()
                                    .properties(JsonValue.from(properties))
                                    .required(declaration
                                            .parameters()
                                            .flatMap(schema -> schema.required())
                                            .orElse(List.of()))
                                    .build())
                            .build());
                });
    }

    private static Map<String, Object> providerSchema(Object schema) {
        Map<String, Object> value = JsonBaseModel.getMapper().convertValue(schema, new TypeReference<>() {});
        if (value.get(SCHEMA_TYPE) instanceof String type) value.put(SCHEMA_TYPE, type.toLowerCase(Locale.ROOT));
        return value;
    }

    /** The ADK part for a visible block; null for thinking blocks, which stay private to provider round trips. */
    private Part toPart(ContentBlock block, Message message, boolean toolsAllowed) {
        if (block.isText()) return Part.fromText(block.asText().text());
        if (block.isToolUse()) {
            if (!toolsAllowed)
                throw new SecurityException("Provider returned a tool call after tool access was disabled");
            var call = block.asToolUse();
            toolTurns.put(call.id(), message.toParam());
            return Part.builder()
                    .functionCall(FunctionCall.builder()
                            .id(call.id())
                            .name(call.name())
                            .args(call._input().convert(new TypeReference<>() {}))
                            .build())
                    .build();
        }
        if (block.isThinking() || block.isRedactedThinking()) return null;
        throw new IllegalStateException("Unsupported provider content block");
    }

    private MessageParam toMessage(Content content) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (Part part : content.parts().orElse(List.of())) {
            if (part.functionCall().isPresent()) {
                // ADK stores only the public function call; replay the complete signed provider turn.
                MessageParam original =
                        toolTurns.get(part.functionCall().orElseThrow().id().orElseThrow());
                if (original == null) throw new IllegalStateException("Missing original provider tool turn");
                return original;
            }
            blocks.add(toBlock(part));
        }
        return MessageParam.builder()
                .role(
                        MODEL_ROLE.equals(content.role().orElse("user"))
                                ? MessageParam.Role.ASSISTANT
                                : MessageParam.Role.USER)
                .contentOfBlockParams(blocks)
                .build();
    }

    private static ContentBlockParam toBlock(Part part) {
        if (part.text().isPresent()) {
            return ContentBlockParam.ofText(
                    TextBlockParam.builder().text(part.text().orElseThrow()).build());
        }
        if (part.functionResponse().isEmpty()) throw new IllegalArgumentException("Unsupported conversation part");
        var response = part.functionResponse().orElseThrow();
        try {
            return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(response.id().orElseThrow())
                    .content(JsonBaseModel.getMapper()
                            .writeValueAsString(response.response().orElse(Map.of())))
                    .build());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not serialize tool result", failure);
        }
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
