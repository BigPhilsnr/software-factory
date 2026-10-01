package dev.shortener.links;

import dev.shortener.redirects.LinkCache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import java.time.Clock;
import java.time.Instant;

class ShortenerServiceTest {
    @Test
    void stopsAfterBoundedCodeCollisions() {
        LinkRepository repository = mock(LinkRepository.class);
        when(repository.create(anyString(), anyString())).thenThrow(new DuplicateKeyException("collision"));
        ShortenerService service = new ShortenerService(repository, new CodeGenerator(), new UrlPolicy(), new LinkCache(Clock.systemUTC()));
        assertThrows(ShortenerService.CapacityException.class, () -> service.create("https://example.com"));
        verify(repository, times(4)).create(anyString(), eq("https://example.com"));
    }

    @Test
    void servesPreviouslyResolvedImmutableLinkFromCacheDuringDatabaseFailure() {
        LinkRepository repository = mock(LinkRepository.class);
        Link link = new Link(1, "abc12345", "https://example.com", Instant.now());
        when(repository.findByCode(link.code())).thenReturn(java.util.Optional.of(link))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        ShortenerService service = new ShortenerService(repository, new CodeGenerator(), new UrlPolicy(), new LinkCache(Clock.systemUTC()));
        assertEquals(link, service.find(link.code().toUpperCase(java.util.Locale.ROOT)).orElseThrow());
        assertEquals(link, service.find(link.code()).orElseThrow());
        verify(repository, times(1)).findByCode(link.code());
    }
}
