package dev.shortener.shorten;

import java.security.SecureRandom;

/** Draws unguessable 8-character codes from the canonical alphabet. */
final class CodeGenerator {
    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int CODE_LENGTH = 8;
    private final SecureRandom random = new SecureRandom();

    String next() {
        char[] result = new char[CODE_LENGTH];
        for (int i = 0; i < result.length; i++) {
            result[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(result);
    }
}
