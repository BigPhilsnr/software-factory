package dev.softwarefactory.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Secret for the audit-chain HMAC. It lives outside the control database, so a database writer
 * cannot rewrite history and recompute valid hashes.
 */
public final class AuditKey {
    public static final int MINIMUM_LENGTH = 32;
    static final String LOCAL_KEY_FILE = "audit.key";
    private static final int GENERATED_BYTES = 32;
    private static final Logger LOG = LoggerFactory.getLogger(AuditKey.class);
    private final byte[] secret;

    private AuditKey(byte[] secret) {
        this.secret = secret.clone();
    }

    public static AuditKey of(String secret) {
        if (secret == null || secret.length() < MINIMUM_LENGTH) {
            throw new IllegalArgumentException("FACTORY_AUDIT_KEY must contain at least " + MINIMUM_LENGTH + " characters");
        }
        return new AuditKey(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Uses the configured key; otherwise a development key persisted under {@code .runs/} (created on
     * first use with owner-only permissions). Live runs refuse to start without a configured key.
     */
    public static AuditKey resolve(String configured, Path projectRoot) throws IOException {
        if (configured != null) return of(configured);
        Path file = projectRoot.resolve(".runs").resolve(LOCAL_KEY_FILE);
        if (!Files.isRegularFile(file)) create(file);
        if (Files.isSymbolicLink(file)) throw new SecurityException("Audit key file cannot be a symbolic link: " + file);
        String local = Files.readString(file, StandardCharsets.UTF_8).strip();
        LOG.warn("FACTORY_AUDIT_KEY is not set; using the development audit key in {}. Set FACTORY_AUDIT_KEY for live runs.", file);
        return of(local);
    }

    private static void create(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        byte[] random = new byte[GENERATED_BYTES];
        new SecureRandom().nextBytes(random);
        Path temporary = Files.createTempFile(file.getParent(), LOCAL_KEY_FILE, ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temporary, HexFormat.of().formatHex(random) + "\n", StandardCharsets.UTF_8);
            // Publish atomically and never replace a key another process created first.
            Files.createLink(file, temporary);
        } catch (FileAlreadyExistsException concurrentlyCreated) {
            LOG.debug("Audit key file was created concurrently: {}", file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    byte[] secret() { return secret.clone(); }
}
