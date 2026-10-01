package dev.softwarefactory.serialization;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void writesBrowserCompatibleDatesAndReadsLegacyTimestamps() throws Exception {
        Instant instant = Instant.parse("2026-10-01T00:00:00Z");
        assertEquals("\"2026-10-01T00:00:00Z\"", Json.MAPPER.writeValueAsString(instant));
        assertEquals(instant, Json.MAPPER.readValue(Long.toString(instant.getEpochSecond()), Instant.class));
    }
}
