package dev.shortener.links;

import java.security.SecureRandom;

public final class CodeGenerator {
    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String next() {
        char[] result = new char[8];
        for (int i = 0; i < result.length; i++) {
            result[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(result);
    }
}
