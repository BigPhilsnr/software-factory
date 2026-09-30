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
}
