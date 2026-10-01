package dev.softwarefactory.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlatformUtilitiesTest {
    @Test
    void loggedTextCannotStartANewLogLine() {
        assertEquals("run-1 INFO forged entry", LogText.singleLine("run-1\nINFO forged entry"));
        assertEquals("a  b", LogText.singleLine("a\r\nb"));
        assertEquals("null", LogText.singleLine(null));
    }

    @Test
    void theWorkspaceIsTheCheckoutEvenWhenStartedInsideTheFactoryModule() {
        Path checkout = Path.of("/work/software-factory");
        assertEquals(checkout, WorkspaceRoot.from(checkout.resolve("factory")));
        assertEquals(checkout, WorkspaceRoot.from(checkout));
        assertEquals(checkout.resolve("shortener"), WorkspaceRoot.from(checkout.resolve("shortener/../shortener")));
        assertEquals(Path.of("/"), WorkspaceRoot.from(Path.of("/")));
    }

    @Test
    void theLeastRecentlyUsedEntryIsEvictedBeyondCapacity() {
        Map<String, Integer> cache = LruMap.create(2);
        cache.put("a", 1);
        cache.put("b", 2);
        assertEquals(1, cache.get("a"));
        cache.put("c", 3);
        assertFalse(cache.containsKey("b"), "b was used least recently");
        assertTrue(cache.containsKey("a") && cache.containsKey("c"));
    }

    @Test
    void infrastructureFailuresKeepTheirCause() {
        var cause = new IllegalStateException("socket closed");
        assertEquals(cause, new InfrastructureException("Control database unavailable", cause).getCause());
        assertEquals("Docker missing", new InfrastructureException("Docker missing").getMessage());
    }
}
