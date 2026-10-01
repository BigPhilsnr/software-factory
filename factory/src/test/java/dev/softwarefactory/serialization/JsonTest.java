package dev.softwarefactory.serialization;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class JsonTest {
    @Test void writesBrowserCompatibleDatesAndReadsLegacyTimestamps() throws Exception {
        Instant instant = Instant.parse("2026-10-01T00:00:00Z");
        assertEquals("\"2026-10-01T00:00:00Z\"", Json.MAPPER.writeValueAsString(instant));
        assertEquals(instant, Json.MAPPER.readValue(Long.toString(instant.getEpochSecond()), Instant.class));
    }
}
