package dev.softwarefactory.operator.web;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** ADK entry point: routes user commands to the same durable control plane as the operator UI. */
final class FactoryAgent extends BaseAgent {
    private static final Logger LOG = LoggerFactory.getLogger(FactoryAgent.class);
    private final OperatorCommands commands;

    FactoryAgent(FactoryService factory) {
        super(
                "software_factory",
                "Feature requests, run progress, evidence and human approvals. Send /help to begin.",
                null,
                null,
                null);
        commands = new OperatorCommands(factory);
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext context) {
        return Flowable.fromCallable(() -> {
            String input = context.userContent().map(Content::text).orElse("");
            String answer;
            try {
                answer = commands.handle(context.session().id(), input);
            } catch (IllegalArgumentException
                    | IllegalStateException
                    | NotFoundException
                    | ServiceUnavailableException failure) {
                answer = "Action not performed: " + failure.getMessage();
            } catch (Exception failure) {
                LOG.error("Chat request failed", failure);
                answer =
                        "The request failed. For chat, check the configured Anthropic key/model and provider availability; for workflow commands, check the operator page. You can retry. Error: "
                                + failure.getClass().getSimpleName();
            }
            return Event.builder()
                    .author(name())
                    .invocationId(context.invocationId())
                    .content(Content.builder()
                            .role("model")
                            .parts(Part.fromText(answer))
                            .build())
                    .build();
        });
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext context) {
        return runAsyncImpl(context);
    }
}
