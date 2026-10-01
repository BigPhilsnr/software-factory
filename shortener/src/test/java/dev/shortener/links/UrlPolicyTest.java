package dev.shortener.links;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class UrlPolicyTest {
    private final UrlPolicy policy = new UrlPolicy();

    @Test
    void acceptsOnlyAbsoluteHttpUrlsWithoutCredentials() {
        assertEquals("https://example.com/a?q=1", policy.validate("https://example.com/a?q=1"));
        for (String invalid : new String[]{"ftp://example.com", "http://user:pass@example.com", "not a url", "http://", "http://example.com\nInjected: yes"}) {
            assertThrows(IllegalArgumentException.class, () -> policy.validate(invalid), invalid);
        }
    }

    @Test
    void enforcesUtf8ByteLimit() {
        String prefix = "https://example.com/";
        assertDoesNotThrow(() -> policy.validate(prefix + "x".repeat(2048 - prefix.length())));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(prefix + "x".repeat(2049 - prefix.length())));
    }

    @Test void rejectsLocalAndNonPublicLiteralTargetsWithoutFetchingThem() {
        for (String host : new String[]{"localhost", "LOCALHOST.", "app.localhost", "service.internal", "router",
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "0.0.0.0",
            "100.64.0.1", "224.0.0.1", "0177.0.0.1", "[::1]", "[fc00::1]", "[fe80::1]", "[::ffff:127.0.0.1]"}) {
            assertThrows(IllegalArgumentException.class, () -> policy.validate("http://" + host + "/x"), host);
        }
        assertDoesNotThrow(() -> policy.validate("https://8.8.8.8/x"));
        assertDoesNotThrow(() -> policy.validate("https://[2606:4700:4700::1111]/x"));
        assertThrows(IllegalArgumentException.class, () -> new UrlPolicy("https://short.example").validate("https://SHORT.example/loop"));
    }
}
