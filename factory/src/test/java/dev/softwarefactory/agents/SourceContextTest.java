package dev.softwarefactory.agents;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceContextTest {
    @TempDir
    Path root;

    @Test
    void includesProductButExcludesFactorySecretsBuildOutputAndSymlinks() throws Exception {
        Files.createDirectories(root.resolve("factory/src"));
        Files.writeString(root.resolve("factory/src/Engine.java"), "CONTROL_PLANE_SENTINEL".repeat(5000));
        Files.createDirectories(root.resolve("shortener/src"));
        Files.writeString(root.resolve("shortener/src/Link.java"), "PRODUCT_SENTINEL");
        Files.writeString(root.resolve(".env"), "SECRET_SENTINEL");
        Files.createSymbolicLink(root.resolve("shortener/src/Secret.java"), root.resolve(".env"));
        Files.createDirectories(root.resolve("shortener/target"));
        Files.writeString(root.resolve("shortener/target/Fake.java"), "BUILD_SENTINEL");
        String context = SourceContext.read(root);
        assertTrue(context.contains("PRODUCT_SENTINEL"));
        assertFalse(context.contains("CONTROL_PLANE_SENTINEL"));
        assertFalse(context.contains("SECRET_SENTINEL"));
        assertFalse(context.contains("BUILD_SENTINEL"));
        assertFalse(context.contains("OMITTED"));
    }

    @Test
    void namesFilesDroppedForSizeOrBudget() throws Exception {
        Files.createDirectories(root.resolve("shortener/src"));
        Files.writeString(root.resolve("shortener/src/Huge.java"), "x".repeat(SourceContext.MAX_FILE_BYTES + 1));
        Files.writeString(root.resolve("shortener/src/Small.java"), "class Small {}");
        String context = SourceContext.read(root);
        assertTrue(context.contains("class Small {}"));
        assertTrue(context.contains("OMITTED (size/budget): shortener/src/Huge.java"));
    }
}
