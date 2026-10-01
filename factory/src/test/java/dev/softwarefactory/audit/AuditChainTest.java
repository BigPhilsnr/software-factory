package dev.softwarefactory.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.softwarefactory.governance.Hashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditChainTest {
    private static final Instant AT = Instant.parse("2026-01-01T00:00:00.123456Z");
    private final AuditChain chain = new AuditChain(AuditKey.of("unit-test-audit-key-0123456789abcdef"));

    @Test
    void legacySchemeReproducesHistoricalHashes() {
        String expected = Hashes.sha256(AuditChain.GENESIS + "|1|" + AT + "|TYPE|detail|state={}");
        assertEquals(expected, chain.hash(AuditChain.LEGACY_SHA256, AuditChain.GENESIS, 1, AT, "TYPE", "detail", "{}"));
    }

    @Test
    void keyedSchemeSeparatesFieldsAndDependsOnTheKey() {
        String hash = chain.hash(AuditChain.HMAC_SHA256, AuditChain.GENESIS, 1, AT, "A|B", "C", "{}");
        assertNotEquals(
                hash,
                chain.hash(AuditChain.HMAC_SHA256, AuditChain.GENESIS, 1, AT, "A", "B|C", "{}"),
                "Moving a separator between fields must change the hash");
        assertNotEquals(
                hash,
                new AuditChain(AuditKey.of("another-unit-test-audit-key-0123456789"))
                        .hash(AuditChain.HMAC_SHA256, AuditChain.GENESIS, 1, AT, "A|B", "C", "{}"));
        assertNotEquals(
                chain.hash(AuditChain.HMAC_SHA256, AuditChain.GENESIS, 1, AT, "T", "D", null),
                chain.hash(AuditChain.HMAC_SHA256, AuditChain.GENESIS, 1, AT, "T", "D", ""));
        assertThrows(IllegalArgumentException.class, () -> chain.hash(3, AuditChain.GENESIS, 1, AT, "T", "D", null));
    }

    @Test
    void shortKeysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AuditKey.of("short"));
    }

    @Test
    void developmentKeyIsCreatedOnceWithOwnerOnlyPermissions(@TempDir Path root) throws Exception {
        AuditKey first = AuditKey.resolve(null, root);
        Path file = root.resolve(".runs").resolve(AuditKey.LOCAL_KEY_FILE);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        String content = Files.readString(file);
        AuditKey second = AuditKey.resolve(null, root);
        assertEquals(content, Files.readString(file));
        assertEquals(
                new AuditChain(first).hash(AuditChain.HMAC_SHA256, AuditChain.GENESIS, 1, AT, "T", "D", null),
                new AuditChain(second).hash(AuditChain.HMAC_SHA256, AuditChain.GENESIS, 1, AT, "T", "D", null));
        assertNotEquals(
                new AuditChain(first).hash(AuditChain.HMAC_SHA256, AuditChain.GENESIS, 1, AT, "T", "D", null),
                new AuditChain(AuditKey.resolve("configured-audit-key-0123456789abcdef", root))
                        .hash(AuditChain.HMAC_SHA256, AuditChain.GENESIS, 1, AT, "T", "D", null),
                "A configured key takes precedence");
    }
}
