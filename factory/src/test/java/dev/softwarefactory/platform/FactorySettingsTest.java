package dev.softwarefactory.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FactorySettingsTest {
    @Test
    void defaultsAreDefinedOnceAndLiveModeNeedsBothKeys() {
        var defaults = FactorySettings.from(Map.of());
        assertEquals(FactorySettings.DEFAULT_MODEL, defaults.model());
        assertEquals(24, defaults.maxModelCalls());
        assertEquals(80, defaults.chatDailyRequests());
        assertFalse(defaults.liveReady());
        assertTrue(defaults.liveBlocker().contains("ANTHROPIC_API_KEY"));
        var providerOnly = FactorySettings.from(Map.of("ANTHROPIC_API_KEY", "key"));
        assertFalse(providerOnly.liveReady());
        assertTrue(providerOnly.liveBlocker().contains("FACTORY_AUDIT_KEY"));
        assertTrue(FactorySettings.from(Map.of("ANTHROPIC_API_KEY", "key", "FACTORY_AUDIT_KEY", "k".repeat(32)))
                .liveReady());
    }

    @Test
    void deadlinesDependOnRole() {
        var settings = FactorySettings.from(
                Map.of("FACTORY_CHAT_DEADLINE_SECONDS", "60", "FACTORY_RUN_DEADLINE_SECONDS", "1200"));
        assertEquals(Duration.ofSeconds(60), settings.deadlineFor("project_chat"));
        assertEquals(Duration.ofSeconds(1200), settings.deadlineFor("implementer"));
    }

    @Test
    void rejectsOutOfRangeOrMalformedNumbers() {
        assertThrows(
                IllegalArgumentException.class, () -> FactorySettings.from(Map.of("FACTORY_MAX_MODEL_CALLS", "0")));
        assertThrows(
                IllegalArgumentException.class, () -> FactorySettings.from(Map.of("FACTORY_MAX_MODEL_CALLS", "101")));
        assertThrows(
                IllegalArgumentException.class,
                () -> FactorySettings.from(Map.of("FACTORY_CHAT_DAILY_REQUESTS", "many")));
    }

    @Test
    void toStringNeverContainsSecrets() {
        String text = FactorySettings.from(Map.of(
                        "CONTROL_DB_PASSWORD", "db-secret", "FACTORY_AUDIT_KEY", "audit-secret-0123456789abcdef0123"))
                .toString();
        assertFalse(text.contains("db-secret"));
        assertFalse(text.contains("audit-secret"));
    }
}
