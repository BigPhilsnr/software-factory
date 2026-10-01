package dev.softwarefactory.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 digests that identify reviewed content, and their constant-time comparison. */
public final class Hashes {
    private Hashes() {}

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Constant-time comparison of hex digests; null never matches. */
    public static boolean same(String expected, String actual) {
        return expected != null
                && actual != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }
}
