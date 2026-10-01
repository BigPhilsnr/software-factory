package dev.softwarefactory.operator.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Page-issued capability for local operator mutations. Never a provider API key. */
public record OperatorToken(String value) {
    /** Constant-time comparison, so response timing does not reveal a matching prefix. */
    public boolean matches(String presented) {
        return presented != null
                && MessageDigest.isEqual(
                        value.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
