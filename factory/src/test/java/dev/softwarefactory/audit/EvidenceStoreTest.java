package dev.softwarefactory.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceStoreTest {
    @TempDir
    Path root;

    @Test
    void identicalRetriesAreIdempotentButDifferentBytesCannotReplaceEvidence() throws Exception {
        var store = new EvidenceStore(root);
        String run = java.util.UUID.randomUUID().toString();
        String first = store.write(run, "task-v1", "café");
        assertEquals(first, store.write(run, "task-v1", "café"));
        assertThrows(IllegalStateException.class, () -> store.write(run, "task-v1", "different"));
        assertEquals("café", Files.readString(store.path(run, "task-v1")));
        assertEquals(
                first, dev.softwarefactory.governance.Hashes.sha256(Files.readAllBytes(store.path(run, "task-v1"))));
    }
}
