package dev.softwarefactory.operator.web;

import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.softwarefactory.agents.ModelClients;
import dev.softwarefactory.configuration.FactorySettings;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FactoryServiceTest {
    @TempDir Path root;

    @Test void fullCapacityRejectsBeforeTouchingDecisionPersistence() {
        var settings = FactorySettings.from(Map.of());
        // A null repository makes any attempted persistence fail differently.
        try (var clients = new ModelClients(settings); var service = new FactoryService(root, null, settings, clients, Set.of("one", "two"))) {
            assertThrows(ServiceUnavailableException.class, () -> service.approve("third", "hash"));
            assertThrows(ServiceUnavailableException.class, () -> service.clarify("third", "answer"));
            assertThrows(ServiceUnavailableException.class, () -> service.revise("third", "task", "feedback"));
            assertThrows(ServiceUnavailableException.class, () -> service.reject("third", "a".repeat(64)));
        }
    }

    @Test void rejectionRequiresAnExactReviewedHash() {
        var settings = FactorySettings.from(Map.of());
        try (var clients = new ModelClients(settings); var service = new FactoryService(root, null, settings, clients)) {
            assertThrows(IllegalArgumentException.class, () -> service.reject("run", null));
            assertThrows(IllegalArgumentException.class, () -> service.reject("run", "not-a-hash"));
        }
    }
}
