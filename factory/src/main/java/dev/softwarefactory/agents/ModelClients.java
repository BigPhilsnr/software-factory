package dev.softwarefactory.agents;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import dev.softwarefactory.agents.tools.PublicWebReader;
import dev.softwarefactory.agents.tools.ToolSession;
import dev.softwarefactory.configuration.FactorySettings;
import dev.softwarefactory.workflow.WorkflowConflictException;

/** Process-wide provider and web clients: connection pools and dispatchers are shared, then closed once. */
public final class ModelClients implements AutoCloseable {
    private final FactorySettings settings;
    private AnthropicClient anthropic;
    private PublicWebReader web;
    private boolean closed;

    public ModelClients(FactorySettings settings) { this.settings = settings; }

    public synchronized AnthropicClient anthropic() {
        requireOpen();
        if (!settings.providerKeyConfigured()) throw new WorkflowConflictException("ANTHROPIC_API_KEY is required for live generation");
        if (anthropic == null) {
            anthropic = AnthropicOkHttpClient.builder().fromEnv()
                .timeout(ToolSession.MAX_REQUEST_TIMEOUT)
                // No SDK retries: every provider request must pass the run/chat budget reservation and
                // audit in ToolSession first, and an automatic retry could repeat a paid call unseen.
                // Failures surface to the workflow's bounded, recorded retry policy instead.
                .maxRetries(0)
                .build();
        }
        return anthropic;
    }

    public synchronized PublicWebReader web() {
        requireOpen();
        if (web == null) web = new PublicWebReader();
        return web;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Model clients are closed");
    }

    @Override public synchronized void close() {
        closed = true;
        if (web != null) web.close();
        if (anthropic != null) anthropic.close();
    }
}
