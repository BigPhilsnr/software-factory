package dev.softwarefactory.platform;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Process configuration read once from the environment and validated before use.
 * Secrets are not retained here: the provider SDK reads its own key, and the audit key
 * is resolved separately by {@code AuditKey}.
 */
public record FactorySettings(
        boolean providerKeyConfigured,
        String model,
        int maxModelCalls,
        int chatDailyRequests,
        String operator,
        Duration chatDeadline,
        Duration runDeadline,
        String controlDatabaseUrl,
        String controlDatabaseUser,
        String controlDatabasePassword,
        Path mavenRepository,
        String auditKey) {
    public static final String DEFAULT_MODEL = "claude-sonnet-4-5";
    static final int DEFAULT_MAX_MODEL_CALLS = 24;
    static final int MAX_MODEL_CALLS_LIMIT = 100;
    static final int DEFAULT_CHAT_DAILY_REQUESTS = 80;
    static final int CHAT_DAILY_REQUESTS_LIMIT = 10_000;
    static final int DEFAULT_CHAT_DEADLINE_SECONDS = 180;
    static final int DEFAULT_RUN_DEADLINE_SECONDS = 900;
    static final int MAX_DEADLINE_SECONDS = 3_600;
    static final String DEFAULT_CONTROL_DATABASE_URL = "jdbc:postgresql://localhost:5434/control";
    static final String DEFAULT_CONTROL_DATABASE_CREDENTIAL = "control";

    public FactorySettings {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(chatDeadline, "chatDeadline");
        Objects.requireNonNull(runDeadline, "runDeadline");
        Objects.requireNonNull(mavenRepository, "mavenRepository");
    }

    public static FactorySettings fromEnvironment() {
        return from(System.getenv());
    }

    public static FactorySettings from(Map<String, String> environment) {
        String home = System.getProperty("user.home");
        return new FactorySettings(
                !text(environment, "ANTHROPIC_API_KEY", "").isBlank(),
                text(environment, "CLAUDE_MODEL", DEFAULT_MODEL),
                bounded(environment, "FACTORY_MAX_MODEL_CALLS", DEFAULT_MAX_MODEL_CALLS, MAX_MODEL_CALLS_LIMIT),
                bounded(
                        environment,
                        "FACTORY_CHAT_DAILY_REQUESTS",
                        DEFAULT_CHAT_DAILY_REQUESTS,
                        CHAT_DAILY_REQUESTS_LIMIT),
                text(environment, "FACTORY_OPERATOR", System.getProperty("user.name", "operator")),
                Duration.ofSeconds(bounded(
                        environment,
                        "FACTORY_CHAT_DEADLINE_SECONDS",
                        DEFAULT_CHAT_DEADLINE_SECONDS,
                        MAX_DEADLINE_SECONDS)),
                Duration.ofSeconds(bounded(
                        environment,
                        "FACTORY_RUN_DEADLINE_SECONDS",
                        DEFAULT_RUN_DEADLINE_SECONDS,
                        MAX_DEADLINE_SECONDS)),
                text(environment, "CONTROL_DB_URL", DEFAULT_CONTROL_DATABASE_URL),
                text(environment, "CONTROL_DB_USER", DEFAULT_CONTROL_DATABASE_CREDENTIAL),
                text(environment, "CONTROL_DB_PASSWORD", DEFAULT_CONTROL_DATABASE_CREDENTIAL),
                Path.of(text(
                        environment,
                        "FACTORY_MAVEN_REPOSITORY",
                        Path.of(home, ".m2", "repository").toString())),
                emptyToNull(environment.get("FACTORY_AUDIT_KEY")));
    }

    /** Live generation needs a provider key and an audit key held outside the control database. */
    public boolean liveReady() {
        return providerKeyConfigured && auditKey != null;
    }

    /** Human-readable reason live generation is unavailable, or empty when it is available. */
    public String liveBlocker() {
        if (!providerKeyConfigured) return "Add ANTHROPIC_API_KEY to .env and restart for live features.";
        if (auditKey == null)
            return "Set FACTORY_AUDIT_KEY (at least 32 characters) in .env and restart for live features.";
        return "";
    }

    /** Invocation deadline: conversational answers are short; engineering tasks may use several tool rounds. */
    public Duration deadlineFor(String role) {
        return "project_chat".equals(role) ? chatDeadline : runDeadline;
    }

    @Override
    public String toString() {
        // Never print database credentials or the audit key.
        return "FactorySettings[model=" + model + ", maxModelCalls=" + maxModelCalls + ", chatDailyRequests="
                + chatDailyRequests + ", chatDeadline=" + chatDeadline + ", runDeadline=" + runDeadline + ", liveReady="
                + liveReady() + "]";
    }

    private static String text(Map<String, String> environment, String name, String fallback) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static int bounded(Map<String, String> environment, String name, int fallback, int maximum) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) return fallback;
        int parsed;
        try {
            parsed = Integer.parseInt(value.strip());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be an integer in 1.." + maximum, invalid);
        }
        if (parsed < 1 || parsed > maximum) throw new IllegalArgumentException(name + " must be in 1.." + maximum);
        return parsed;
    }
}
