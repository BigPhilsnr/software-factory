package dev.softwarefactory.generation.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolSessionTest {
    @Test
    void generationStopBlocksFurtherToolsAndProviderReservationsWithoutLeakingTheDiagnostic() {
        var reservations = new AtomicInteger();
        var events = new ArrayList<String>();
        var session = new ToolSession(reservations::incrementAndGet, (event, detail) -> events.add(detail));
        var exhausted = new dev.softwarefactory.platform.GenerationUnavailableException("Compiler diagnostic");
        String result = session.invoke("check_draft", "", () -> {
            throw exhausted;
        });
        assertTrue(result.contains("stopped"));
        assertFalse(result.contains("Compiler diagnostic"));
        assertSame(exhausted, assertThrows(exhausted.getClass(), session::reserveRequest));
        assertSame(exhausted, assertThrows(exhausted.getClass(), session::reserveSearchRequest));
        assertSame(
                exhausted,
                assertThrows(
                        exhausted.getClass(),
                        () -> session.invoke("write_draft_file", "", () -> {
                            throw new AssertionError("No edits after a terminal stop");
                        })));
        assertEquals(0, reservations.get());
        assertTrue(events.getLast().contains("check_draft:STOPPED"));
        assertEquals(2, events.size());
    }

    @Test
    void countsRequestsWithoutACallLimit() {
        AtomicInteger calls = new AtomicInteger();
        var session = new ToolSession(calls::incrementAndGet, (event, detail) -> {});
        for (int i = 0; i < 150; i++) session.reserveRequest();
        assertEquals(150, calls.get());
        assertTrue(session.toolsAllowed());
    }

    @Test
    void auditsHashesAndOutcomesWithoutLeakingArgumentsOrProviderErrors() throws Exception {
        var events = new ArrayList<String>();
        var session = new ToolSession(() -> {}, (event, detail) -> events.add(event + ":" + detail));
        assertTrue(session.invoke("read_file", "private-input", () -> "output").contains("\noutput\n"));
        String failure = session.invoke("search_web", "query", () -> {
            throw new IllegalStateException("SECRET_SENTINEL");
        });
        assertFalse(failure.contains("SECRET_SENTINEL"));
        assertTrue(session.summary().contains("search_web ERROR"));
        assertFalse(events.toString().contains("private-input"));
        assertTrue(events.getFirst().contains("inputSha256="));
        assertTrue(events.getLast().contains("outputSha256="));
        for (int i = 2; i < 12; i++) session.invoke("current_time", "", () -> "time");
        assertThrows(SecurityException.class, () -> session.invoke("current_time", "", () -> "time"));
    }

    @Test
    void searchesAreCountedWithoutReservingAFinalCallSlot() {
        AtomicInteger reservations = new AtomicInteger();
        var session = new ToolSession(reservations::incrementAndGet, (event, detail) -> {});
        for (int i = 0; i < 150; i++) session.reserveSearchRequest();
        assertEquals(150, reservations.get());
        assertTrue(session.toolsAllowed());
    }

    @Test
    void requestTimeoutsNeverExceedTheInvocationDeadline() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);
        var soon = new ToolSession(() -> {}, (event, detail) -> {}, now.plusSeconds(30), clock);
        assertEquals(java.time.Duration.ofSeconds(30), soon.requestTimeout());
        var later = new ToolSession(() -> {}, (event, detail) -> {}, now.plusSeconds(3600), clock);
        assertEquals(ToolSession.MAX_REQUEST_TIMEOUT, later.requestTimeout());
        var expired = new ToolSession(() -> {}, (event, detail) -> {}, now.minusSeconds(1), clock);
        assertThrows(IllegalStateException.class, expired::requestTimeout);
    }

    @Test
    void lateCallbacksAfterCloseNeitherReserveNorAudit() {
        AtomicInteger reservations = new AtomicInteger();
        var events = new ArrayList<String>();
        var session = new ToolSession(reservations::incrementAndGet, (event, detail) -> events.add(event));
        session.close();
        assertThrows(IllegalStateException.class, session::reserveRequest);
        assertThrows(IllegalStateException.class, () -> session.invoke("read_file", "path", () -> "source"));
        session.recordFinalization();
        assertEquals(0, reservations.get());
        assertTrue(events.isEmpty());
    }

    @Test
    void toolLimitAlsoSwitchesTheModelToFinalResponse() throws Exception {
        var session = new ToolSession(() -> {}, (event, detail) -> {});
        for (int i = 0; i < ToolSession.MAX_TOOL_CALLS; i++) session.invoke("read_file", "path", () -> "source");
        session.reserveRequest();
        assertFalse(session.toolsAllowed());
    }
}
