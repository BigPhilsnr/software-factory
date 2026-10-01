package dev.softwarefactory.operator.chat;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import dev.softwarefactory.operator.api.FactoryService;
import dev.softwarefactory.operator.api.NotFoundException;
import dev.softwarefactory.operator.api.ServiceUnavailableException;
import io.reactivex.rxjava3.core.Flowable;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** ADK entry point: routes user commands to the same durable control plane as the operator UI. */
public final class FactoryAgent extends BaseAgent {
    private static final Logger LOG = LoggerFactory.getLogger(FactoryAgent.class);
    private final OperatorCommands commands;

    public FactoryAgent(FactoryService factory) {
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
        return Flowable.fromCallable(() -> Event.builder()
                .author(name())
                .invocationId(context.invocationId())
                .content(Content.builder()
                        .role("model")
                        .parts(Part.fromText(answer(
                                context.session().id(),
                                context.userContent().map(Content::text).orElse(""))))
                        .build())
                .build());
    }

    /** Chat boundary: the operator always gets an answer, and a failure never reveals more than its type. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // Any failure must become a chat reply.
    String answer(String session, String input) {
        try {
            return commands.handle(session, input);
        } catch (IllegalArgumentException
                | IllegalStateException
                | NotFoundException
                | ServiceUnavailableException failure) {
            return "Action not performed: " + failure.getMessage();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(interrupted);
        } catch (IOException | RuntimeException failure) {
            return failed(failure);
        }
    }

    private static String failed(Exception failure) {
        LOG.error("Chat request failed", failure);
        return "The request failed. For chat, check the configured Anthropic key/model and provider availability; for workflow commands, check the operator page. You can retry. Error: "
                + failure.getClass().getSimpleName();
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext context) {
        return runAsyncImpl(context);
    }
}
