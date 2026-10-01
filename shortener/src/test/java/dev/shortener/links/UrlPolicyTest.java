package dev.shortener.links;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlPolicyTest {
    private final UrlPolicy policy = new UrlPolicy(URI.create("https://short.example"));

    @Test
    void acceptsOnlyAbsoluteHttpUrlsWithoutCredentials() {
        assertEquals("https://example.com/a?q=1", policy.validate("https://example.com/a?q=1"));
        for (String invalid : new String[]{"ftp://example.com", "http://user:pass@example.com", "not a url", "http://",
                "http://example.com\nInjected: yes", "//example.com/x", "/relative"}) {
            assertThrows(InvalidLinkException.class, () -> policy.validate(invalid), invalid);
        }
    }

    @Test
    void returnsNormalizedAsciiForm() {
        assertEquals("https://example.com/caf%C3%A9", policy.validate("https://example.com/café"));
        assertThrows(InvalidLinkException.class, () -> policy.validate("https://exämple.com/x"), "non-ASCII host");
    }

    @Test
    void enforcesEncodedByteLimit() {
        String prefix = "https://example.com/";
        assertDoesNotThrow(() -> policy.validate(prefix + "x".repeat(UrlPolicy.MAX_URL_BYTES - prefix.length())));
        assertThrows(InvalidLinkException.class, () -> policy.validate(prefix + "x".repeat(UrlPolicy.MAX_URL_BYTES + 1 - prefix.length())));
        assertThrows(InvalidLinkException.class, () -> policy.validate(prefix + "é".repeat(700)), "expands to 4200 bytes");
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "LOCALHOST.", "app.localhost", "service.internal", "printer.local", "nas.lan",
        "wiki.intranet", "router.home.arpa", "router"})
    void rejectsLocalHostNames(String host) {
        assertThrows(InvalidLinkException.class, () -> policy.validate("http://" + host + "/x"), host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "0.0.0.0",
        "100.64.0.1", "224.0.0.1", "255.255.255.255", "192.0.0.1", "192.0.2.1", "192.88.99.1", "198.18.0.1",
        "198.51.100.7", "203.0.113.9"})
    void rejectsNonPublicIpv4Literals(String host) {
        assertThrows(InvalidLinkException.class, () -> policy.validate("http://" + host + "/x"), host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0177.0.0.1", "0x7f.0.0.1", "0x7f000001", "2130706433", "127.1", "127.0.0.0x", "10.0.0.0x",
        "0x", "1.2.3.4.5", "8.8.8.08", "1.2.3", "example.123", "a..example.com"})
    void rejectsAmbiguousNumericAndMalformedHostsWithoutDns(String host) {
        assertThrows(InvalidLinkException.class, () -> policy.validate("http://" + host + "/x"), host);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[::1]", "[::]", "[fc00::1]", "[fe80::1]", "[ff02::1]", "[::ffff:127.0.0.1]",
        "[2001:db8::1]", "[2002:7f00:1::1]", "[2001::1]", "[64:ff9b::7f00:1]"})
    void rejectsNonPublicIpv6Literals(String host) {
        assertThrows(InvalidLinkException.class, () -> policy.validate("http://" + host + "/x"), host);
    }

    @Test
    void acceptsPublicLiteralsAndNames() {
        for (String target : new String[]{"https://8.8.8.8/x", "https://[2606:4700:4700::1111]/x", "https://xn--bcher-kva.example/x",
                "https://sub.example.co.uk/x", "https://example.com./x"}) {
            assertDoesNotThrow(() -> policy.validate(target), target);
        }
    }

    @Test
    void rejectsTheShortenerAndItsSubdomains() {
        for (String target : new String[]{"https://SHORT.example/loop", "https://short.example./loop", "https://a.short.example/loop"}) {
            assertThrows(InvalidLinkException.class, () -> policy.validate(target), target);
        }
        assertDoesNotThrow(() -> policy.validate("https://notshort.example/x"));
    }
}
