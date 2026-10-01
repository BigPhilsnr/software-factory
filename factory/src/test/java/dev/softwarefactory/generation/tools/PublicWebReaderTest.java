package dev.softwarefactory.generation.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.util.List;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;

class PublicWebReaderTest {
    @Test
    void rejectsPrivateReservedAndMetadataAddressesIncludingIpv6() throws Exception {
        for (String ip : List.of(
                "127.0.0.1",
                "0.0.0.0",
                "10.1.2.3",
                "169.254.169.254",
                "172.16.4.5",
                "192.168.1.1",
                "100.64.0.1",
                "198.18.0.1",
                "192.0.2.1",
                "224.0.0.1",
                "::1",
                "fc00::1",
                "fe80::1",
                "::ffff:127.0.0.1",
                "2002:7f00:1::",
                "2001:db8::1")) {
            assertFalse(PublicAddresses.isPublic(InetAddress.getByName(ip)), ip);
        }
        assertTrue(PublicAddresses.isPublic(InetAddress.getByName("93.184.216.34")));
        assertTrue(PublicAddresses.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void rejectsNonHttpsCredentialsPortsAndLocalLiteralUrls() {
        for (String url : List.of(
                "file:///etc/passwd",
                "http://example.com",
                "https://user:pass@example.com/",
                "https://example.com:8443/",
                "https://localhost/",
                "https://127.0.0.1/",
                "https://[::1]/",
                "https://[::ffff:127.0.0.1]/",
                "https://metadata.internal/")) {
            assertThrows(RuntimeException.class, () -> PublicUrls.validate(url), url);
        }
        assertEquals(
                "example.com",
                PublicUrls.validate("https://example.com/docs?q=java").getHost());
    }

    @Test
    void redirectsStayOnTheApprovedOriginWithoutQueries() {
        HttpUrl origin = HttpUrl.get("https://docs.example.org/guide");
        assertEquals(
                "https://docs.example.org/guide/v2",
                PublicWebReader.sameOriginRedirect(origin, origin, "/guide/v2?session=abc#top")
                        .toString());
        assertEquals(
                "https://docs.example.org/other",
                PublicWebReader.sameOriginRedirect(origin, origin, "https://DOCS.example.org:443/other")
                        .toString());
        for (String location : List.of(
                "https://attacker.example/guide",
                "http://docs.example.org/guide",
                "https://docs.example.org:8443/guide",
                "https://sub.docs.example.org/guide")) {
            assertThrows(
                    SecurityException.class,
                    () -> PublicWebReader.sameOriginRedirect(origin, origin, location),
                    location);
        }
        assertThrows(IllegalStateException.class, () -> PublicWebReader.sameOriginRedirect(origin, origin, null));
    }

    @Test
    void htmlExtractionDoesNotExecuteOrReturnScriptsAndStyles() {
        String text = HtmlText.visible(
                "<html><head><style>secret-style</style></head><body><h1>Title</h1><script>secret-script</script><p>A &amp; B</p></body></html>");
        assertTrue(text.contains("Title"));
        assertTrue(text.contains("A & B"));
        assertFalse(text.contains("secret-script"));
        assertFalse(text.contains("secret-style"));
        assertEquals("First\nSecond", HtmlText.visible("<p>First</p><noscript>hidden</noscript><div>Second</div>"));
    }
}
