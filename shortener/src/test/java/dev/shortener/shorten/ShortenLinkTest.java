package dev.shortener.shorten;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.shortener.link.Link;
import dev.shortener.link.LinkCodes;
import dev.shortener.link.LinkRepository;
import dev.shortener.platform.ShortenerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class ShortenLinkTest {
    private static final String TARGET = "https://example.com";
    private static final String CLIENT = "192.0.2.1";
    private static final int QUOTA = 2;

    private final LinkRepository repository = mock(LinkRepository.class);
    private final ShortenLink shortenLink = new ShortenLink(
            repository,
            new CodeGenerator(),
            new UrlPolicy(URI.create("http://short.example")),
            new CreationRateLimiter(
                    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                    new ShortenerProperties.RateLimit(QUOTA, Duration.ofSeconds(60), 100),
                    new SimpleMeterRegistry()));

    private static Link stored(String code) {
        return new Link(1, code, TARGET, Instant.EPOCH);
    }

    @Test
    void storesTheTargetUnderAGeneratedCode() {
        when(repository.create(anyString(), eq(TARGET))).thenAnswer(call -> stored(call.getArgument(0)));
        Link link = shortenLink.shorten(TARGET, null, CLIENT);
        assertEquals(LinkCodes.canonical(link.code()).orElseThrow(), link.code(), "generated codes are canonical");
    }

    @Test
    void stopsAfterBoundedCodeCollisions() {
        when(repository.create(anyString(), anyString())).thenThrow(new DuplicateKeyException("collision"));
        assertThrows(CodeSpaceExhaustedException.class, () -> shortenLink.shorten(TARGET, null, CLIENT));
        verify(repository, times(4)).create(anyString(), eq(TARGET));
    }

    @Test
    void canonicalizesAliasesAndRejectsReservedWords() {
        when(repository.create("mixed-case", TARGET)).thenReturn(stored("mixed-case"));
        assertEquals(stored("mixed-case"), shortenLink.shorten(TARGET, "Mixed-Case", CLIENT));
        for (String reserved : LinkCodes.RESERVED) {
            assertThrows(
                    InvalidLinkException.class,
                    () -> shortenLink.shorten(TARGET, reserved.toUpperCase(Locale.ROOT), CLIENT));
        }
        assertThrows(InvalidLinkException.class, () -> shortenLink.shorten(TARGET, "x!", CLIENT));
    }

    @Test
    void duplicateAliasIsAConflict() {
        when(repository.create("taken-alias", TARGET)).thenThrow(new DuplicateKeyException("taken"));
        assertThrows(AliasTakenException.class, () -> shortenLink.shorten(TARGET, "taken-alias", CLIENT));
    }

    @Test
    void onlyValidRequestsConsumeQuotaAndNothingIsStoredBeyondIt() {
        when(repository.create(anyString(), eq(TARGET))).thenAnswer(call -> stored(call.getArgument(0)));
        for (int i = 0; i < QUOTA + 1; i++) {
            assertThrows(InvalidLinkException.class, () -> shortenLink.shorten("http://localhost/x", null, CLIENT));
        }
        for (int i = 0; i < QUOTA; i++) shortenLink.shorten(TARGET, null, CLIENT);
        var exceeded =
                assertThrows(CreationRateLimitExceededException.class, () -> shortenLink.shorten(TARGET, null, CLIENT));
        assertEquals(60, exceeded.retryAfterSeconds().orElseThrow());
        verify(repository, times(QUOTA)).create(anyString(), eq(TARGET));
        verify(repository, never()).create(anyString(), eq("http://localhost/x"));
    }
}
