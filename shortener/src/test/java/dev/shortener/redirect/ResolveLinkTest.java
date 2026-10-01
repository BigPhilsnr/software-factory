package dev.shortener.redirect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.shortener.link.Link;
import dev.shortener.link.LinkRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResolveLinkTest {
    private static final Link LINK = new Link(1, "abc12345", "https://example.com", Instant.EPOCH);

    private final LinkRepository repository = mock(LinkRepository.class);
    private final VisitRecorder visits = mock(VisitRecorder.class);
    private final ResolveLink resolveLink = new ResolveLink(repository, visits);

    @Test
    void reservedAndMalformedCodesNeverReachTheDatabase() {
        assertThrows(LinkNotFoundException.class, () -> resolveLink.resolve("logout"));
        assertThrows(LinkNotFoundException.class, () -> resolveLink.resolve("favicon.ico"));
        assertThrows(LinkNotFoundException.class, () -> resolveLink.resolve(null));
        verify(repository, never()).findByCode(anyString());
    }

    @Test
    void resolvesAnySpellingOfTheCodeWithoutCountingAVisit() {
        when(repository.findByCode(LINK.code())).thenReturn(Optional.of(LINK));
        assertEquals(LINK, resolveLink.resolve(LINK.code().toUpperCase(Locale.ROOT)));
        verify(visits, never()).record(anyLong());
    }

    @Test
    void followingALinkRecordsTheVisit() {
        when(repository.findByCode(LINK.code())).thenReturn(Optional.of(LINK));
        assertEquals(LINK, resolveLink.follow(LINK.code()));
        verify(visits).record(LINK.id());
    }

    @Test
    void followingAnUnknownCodeRecordsNothing() {
        when(repository.findByCode("unknown1")).thenReturn(Optional.empty());
        assertThrows(LinkNotFoundException.class, () -> resolveLink.follow("unknown1"));
        verify(visits, never()).record(anyLong());
    }
}
