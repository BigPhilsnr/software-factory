package dev.softwarefactory.agents.tools;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WebAccessPolicyTest {
    @Test void fetchesOnlyExactSearchSourcesWithoutQueriesOrFragments() {
        var policy = new WebAccessPolicy();
        policy.registerSource("https://docs.example.org/guide?source=search");
        assertEquals("https://docs.example.org/guide", policy.approved("https://docs.example.org/guide?leak=private-code#secret"));
        assertThrows(SecurityException.class, () -> policy.approved("https://docs.example.org/leak/private-code"));
        assertThrows(SecurityException.class, () -> policy.approved("https://attacker.example/guide"));
        policy.registerSource("https://127.0.0.1/guide");
        assertThrows(SecurityException.class, () -> policy.approved("https://127.0.0.1/guide"));
    }
}
