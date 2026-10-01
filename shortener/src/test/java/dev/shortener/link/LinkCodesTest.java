package dev.shortener.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LinkCodesTest {
    @Test
    void canonicalCodesAreLowercase() {
        assertEquals(Optional.of("mixed-alias"), LinkCodes.canonical("Mixed-Alias"));
        assertEquals(Optional.of("abcd1234"), LinkCodes.canonical("abcd1234"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "abc", "x!yz", "favicon.ico", "has space", "a-code-that-is-far-longer-than-32-chars"})
    void malformedCodesCanNeverNameALink(String code) {
        assertTrue(LinkCodes.canonical(code).isEmpty());
    }

    @Test
    void reservedWordsCanNeverNameALinkInAnySpelling() {
        for (String reserved : LinkCodes.RESERVED) {
            assertTrue(LinkCodes.canonical(reserved).isEmpty(), reserved);
            assertTrue(LinkCodes.canonical(reserved.toUpperCase(Locale.ROOT)).isEmpty(), reserved);
        }
    }
}
