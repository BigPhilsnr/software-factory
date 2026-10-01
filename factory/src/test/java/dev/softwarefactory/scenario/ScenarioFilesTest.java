package dev.softwarefactory.scenario;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioFilesTest {
    @TempDir
    Path root;

    @Test
    void historicalPathsResolveWithoutChangingSpecificationBytes() throws Exception {
        Path moved = root.resolve("scenarios/bugfix/scenario.json");
        Files.createDirectories(moved.getParent());
        String original = "{\"id\":\"original\"}\n";
        Files.writeString(moved, original);
        Path resolved = ScenarioFiles.resolve(root.resolve("scenarios/bugfix.json"));
        assertEquals(moved, resolved);
        assertEquals(original, Files.readString(resolved));
        assertEquals(moved, ScenarioFiles.resolve(moved));
    }

    @Test
    void customFeatureSpecificationsAreNotRedirected() throws Exception {
        Path custom = root.resolve("requests/feature.json");
        Files.createDirectories(custom.getParent());
        Files.writeString(custom, "{}");
        assertEquals(custom, ScenarioFiles.resolve(custom));
        Path missing = root.resolve("requests/missing.json");
        assertEquals(missing, ScenarioFiles.resolve(missing));
    }

    @Test
    void readReturnsTheParsedScenarioTogetherWithItsExactText() throws Exception {
        Path file = root.resolve("scenarios/demo/scenario.json");
        Files.createDirectories(file.getParent());
        String text = "{ \"id\": \"demo\", \"requirement\": \"Do it\", \"baselineTag\": \"v1\" }\n";
        Files.writeString(file, text);
        ScenarioFiles.Document document = ScenarioFiles.read(root.resolve("scenarios/demo.json"));
        assertEquals(text, document.text(), "Run hashes are computed from the unmodified file content");
        assertEquals("demo", document.spec().id());
        assertEquals(java.util.List.of(), document.spec().tasks());
    }
}
