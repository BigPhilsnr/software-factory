package dev.softwarefactory.agents.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryReaderTest {
    @TempDir
    Path root;

    @Test
    void readsAndSearchesSourcesButRejectsSecretsTraversalAndSymlinks() throws Exception {
        Path source = root.resolve("shortener/src/main/java/Example.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Example {\n // marker\n}\n");
        Files.writeString(root.resolve(".env"), "SECRET_SENTINEL");
        Files.writeString(root.resolve("README.md"), "guide");
        Path credentials = root.resolve("docs/credentials.json");
        Files.createDirectories(credentials.getParent());
        Files.writeString(credentials, "SECRET_SENTINEL");
        Files.createSymbolicLink(root.resolve("docs/linked.md"), root.resolve(".env"));
        Files.createSymbolicLink(root.resolve("docs/nested"), root.resolve("shortener/src"));
        var reader = new RepositoryReader(root);
        assertTrue(reader.list("shortener/").contains("Example.java"));
        assertTrue(reader.read("shortener/src/main/java/Example.java", 2, 1).contains("2:  // marker"));
        assertTrue(reader.search("marker").contains("Example.java:2:"));
        assertFalse(reader.search("SECRET_SENTINEL").contains("SECRET_SENTINEL"));
        for (String path : java.util.List.of(
                ".env",
                "../README.md",
                "docs/credentials.json",
                "docs/linked.md",
                "docs/nested/main/java/Example.java")) {
            assertThrows(SecurityException.class, () -> reader.read(path, 1, 10), path);
        }
        assertThrows(IllegalArgumentException.class, () -> reader.read("README.md", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> reader.git("diff; cat .env"));
    }

    @Test
    void gitInspectionExcludesTrackedSecretsAndDoesNotExecuteCommands() throws Exception {
        Files.writeString(root.resolve("README.md"), "original\n");
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Files.writeString(root.resolve(".env"), "old secret\n");
        git("init", "-q");
        git("add", "README.md", "pom.xml", ".env");
        git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "initial");
        Files.writeString(root.resolve("README.md"), "new documentation\n");
        Files.writeString(root.resolve(".env"), "SECRET_SENTINEL\n");
        Files.delete(root.resolve("pom.xml"));
        var reader = new RepositoryReader(root);
        String diff = reader.git("diff");
        assertTrue(diff.contains("new documentation"));
        assertTrue(diff.contains("deleted file mode"));
        assertFalse(diff.contains("SECRET_SENTINEL"));
        assertFalse(reader.git("status").contains(".env"));
        assertTrue(reader.git("log").contains("initial"));
    }

    private void git(String... args) throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of("git"));
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor());
    }
}
