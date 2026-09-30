package dev.softwarefactory.agents;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SourceContextTest {
    @TempDir Path root;
    @Test void includesProductButExcludesFactorySecretsBuildOutputAndSymlinks() throws Exception {
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
    }
}
