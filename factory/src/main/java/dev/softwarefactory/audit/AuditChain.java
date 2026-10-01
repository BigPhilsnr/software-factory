package dev.softwarefactory.audit;

import dev.softwarefactory.governance.Hashes;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hash schemes for the audit chain. Rows record their scheme so historical rows remain verifiable.
 * <ul>
 * <li>{@link #LEGACY_SHA256}: keyless SHA-256 over {@code |}-separated fields (verification only).</li>
 * <li>{@link #HMAC_SHA256}: HMAC-SHA256 over length-prefixed fields, keyed outside the database.</li>
 * </ul>
 * A chain may move from the legacy scheme to HMAC but never back: once an HMAC row exists, every
 * earlier row is pinned by the keyed hash of its successor.
 */
final class AuditChain {
    static final int LEGACY_SHA256 = 1;
    static final int HMAC_SHA256 = 2;
    static final String GENESIS = "0".repeat(64);
    private static final String ALGORITHM = "HmacSHA256";
    private static final int ABSENT = -1;
    private final SecretKeySpec key;

    AuditChain(AuditKey key) {
        this.key = new SecretKeySpec(key.secret(), ALGORITHM);
    }

    String hash(int scheme, String previous, long sequence, Instant at, String type, String detail, String stateJson) {
        return switch (scheme) {
            case LEGACY_SHA256 ->
                Hashes.sha256(previous + "|" + sequence + "|" + at + "|" + type + "|" + detail
                        + (stateJson == null ? "" : "|state=" + stateJson));
            case HMAC_SHA256 -> hmac(previous, Long.toString(sequence), at.toString(), type, detail, stateJson);
            default -> throw new IllegalArgumentException("Unknown audit hash scheme " + scheme);
        };
    }

    static boolean matches(String expected, String actual) {
        return Hashes.same(expected, actual);
    }

    static boolean isKnownScheme(int scheme) {
        return scheme == LEGACY_SHA256 || scheme == HMAC_SHA256;
    }

    private String hmac(String... fields) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(encode(fields)));
        } catch (NoSuchAlgorithmException | InvalidKeyException unavailable) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", unavailable);
        }
    }

    /** Each field is its UTF-8 length followed by its bytes, so field boundaries cannot be forged. */
    private static byte[] encode(String... fields) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(HMAC_SHA256);
            for (String field : fields) {
                if (field == null) {
                    output.writeInt(ABSENT);
                    continue;
                }
                byte[] value = field.getBytes(StandardCharsets.UTF_8);
                output.writeInt(value.length);
                output.write(value);
            }
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return bytes.toByteArray();
    }
}
