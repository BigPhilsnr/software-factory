package com.example.shortener.domain;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class ShortenerServiceTest {
    @Test
    void stopsAfterBoundedCodeCollisions() {
        LinkRepository repository = mock(LinkRepository.class);
        when(repository.create(anyString(), anyString())).thenThrow(new DuplicateKeyException("collision"));
        ShortenerService service = new ShortenerService(repository, new CodeGenerator(), new UrlPolicy());
        assertThrows(ShortenerService.CapacityException.class, () -> service.create("https://example.com"));
        verify(repository, times(4)).create(anyString(), eq("https://example.com"));
    }
}
