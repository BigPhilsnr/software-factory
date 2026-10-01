package dev.shortener.redirects;
import dev.shortener.links.Link;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LinkCacheTest {
    @Test void evictsLeastRecentlyUsedAndCreationOverridesMisses() {
        var cache=new LinkCache(Clock.systemUTC());
        for(int i=0;i<10000;i++) cache.put(new Link(i,"code"+i,"https://example.com",Instant.now()));
        assertTrue(cache.get("code0").isPresent());
        cache.put(new Link(10000,"extra","https://example.com",Instant.now()));
        assertTrue(cache.get("code0").isPresent());
        assertTrue(cache.get("code1").isEmpty());
        cache.putMiss("alias"); assertTrue(cache.containsMiss("alias"));
        cache.put(new Link(10001,"alias","https://example.com",Instant.now()));
        cache.putMiss("alias");
        assertFalse(cache.containsMiss("alias")); assertTrue(cache.get("alias").isPresent());
    }
}
