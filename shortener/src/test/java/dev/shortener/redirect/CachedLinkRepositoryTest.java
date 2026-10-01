package dev.shortener.redirect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.shortener.link.Link;
import dev.shortener.link.LinkRepository;
import dev.shortener.platform.ShortenerProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class CachedLinkRepositoryTest {
    private static final ShortenerProperties.Cache SETTINGS =
            new ShortenerProperties.Cache(100, Duration.ofMinutes(1), 100, Duration.ofSeconds(2));
    private static final Link LINK = new Link(1, "abc12345", "https://example.com", Instant.EPOCH);

    private final LinkRepository stored = mock(LinkRepository.class);
    private final LinkRepository cached =
            new CachedLinkRepository(stored, new LinkCache(SETTINGS, Ticker.systemTicker()));

    @Test
    void servesPreviouslyResolvedImmutableLinkFromCacheDuringDatabaseFailure() {
        when(stored.findByCode(LINK.code()))
                .thenReturn(Optional.of(LINK))
                .thenThrow(new DataAccessResourceFailureException("offline"));
        assertEquals(LINK, cached.findByCode(LINK.code()).orElseThrow());
        assertEquals(LINK, cached.findByCode(LINK.code()).orElseThrow());
        verify(stored, times(1)).findByCode(LINK.code());
    }

    @Test
    void remembersMissesBriefly() {
        when(stored.findByCode("unknown1")).thenReturn(Optional.empty());
        assertTrue(cached.findByCode("unknown1").isEmpty());
        assertTrue(cached.findByCode("unknown1").isEmpty());
        verify(stored, times(1)).findByCode("unknown1");
    }

    @Test
    void aNewlyCreatedLinkOverridesAnEarlierMissWithoutAnotherLookup() {
        when(stored.findByCode(LINK.code())).thenReturn(Optional.empty());
        when(stored.create(LINK.code(), LINK.targetUrl())).thenReturn(LINK);
        assertTrue(cached.findByCode(LINK.code()).isEmpty());
        assertEquals(LINK, cached.create(LINK.code(), LINK.targetUrl()));
        assertEquals(LINK, cached.findByCode(LINK.code()).orElseThrow());
        verify(stored, times(1)).findByCode(LINK.code());
    }
}
