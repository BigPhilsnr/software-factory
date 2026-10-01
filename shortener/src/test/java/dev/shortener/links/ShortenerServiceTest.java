package dev.shortener.links;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.shortener.ShortenerProperties;
import dev.shortener.redirects.LinkCache;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

class ShortenerServiceTest {
    private static final ShortenerProperties.Cache CACHE =
        new ShortenerProperties.Cache(100, Duration.ofMinutes(1), 100, Duration.ofSeconds(2));

    private final LinkRepository repository = mock(LinkRepository.class);
    private final ShortenerService service = new ShortenerService(repository, new CodeGenerator(),
        new UrlPolicy(URI.create("http://short.example")), new LinkCache(CACHE, Ticker.systemTicker()));

    @Test
    void stopsAfterBoundedCodeCollisions() {
        when(repository.create(anyString(), anyString())).thenThrow(new DuplicateKeyException("collision"));
        LinkDraft draft = service.prepare("https://example.com", null);
        assertThrows(ShortenerService.CapacityException.class, () -> service.create(draft));
        verify(repository, times(4)).create(anyString(), eq("https://example.com"));
    }

    @Test
    void canonicalizesAliasesAndRejectsReservedWords() {
        assertEquals(new LinkDraft("https://example.com", "mixed-case"), service.prepare("https://example.com", "Mixed-Case"));
        for (String reserved : LinkCodes.RESERVED) {
            assertThrows(InvalidLinkException.class, () -> service.prepare("https://example.com", reserved.toUpperCase(Locale.ROOT)));
        }
        assertThrows(InvalidLinkException.class, () -> service.prepare("https://example.com", "x!"));
    }

    @Test
    void duplicateAliasIsAConflict() {
        when(repository.create("taken-alias", "https://example.com")).thenThrow(new DuplicateKeyException("taken"));
        LinkDraft draft = service.prepare("https://example.com", "taken-alias");
        assertThrows(ShortenerService.AliasConflictException.class, () -> service.create(draft));
    }

    @Test
    void reservedAndMalformedCodesNeverReachTheDatabase() {
        assertTrue(service.find("logout").isEmpty());
        assertTrue(service.find("favicon.ico").isEmpty());
        assertTrue(service.find(null).isEmpty());
        verify(repository, never()).findByCode(anyString());
    }

    @Test
    void servesPreviouslyResolvedImmutableLinkFromCacheDuringDatabaseFailure() {
        Link link = new Link(1, "abc12345", "https://example.com", Instant.now());
        when(repository.findByCode(link.code())).thenReturn(Optional.of(link))
            .thenThrow(new DataAccessResourceFailureException("offline"));
        assertEquals(link, service.find(link.code().toUpperCase(Locale.ROOT)).orElseThrow());
        assertEquals(link, service.find(link.code()).orElseThrow());
        verify(repository, times(1)).findByCode(link.code());
    }

    @Test
    void remembersMissesBriefly() {
        when(repository.findByCode("unknown1")).thenReturn(Optional.empty());
        assertTrue(service.find("unknown1").isEmpty());
        assertTrue(service.find("unknown1").isEmpty());
        verify(repository, times(1)).findByCode("unknown1");
    }

    @Test
    void missingStatisticsForAMissingLinkIsNotFound() {
        Link link = new Link(9, "gone1234", "https://example.com", Instant.now());
        when(repository.statistics(9)).thenReturn(Optional.empty());
        assertThrows(LinkNotFoundException.class, () -> service.statistics(link));
    }
}
