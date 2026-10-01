package dev.softwarefactory.agents.tools;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolSessionTest {
    @Test void countsEveryRequestAndStopsBeforeProviderWhenBudgetExhausted() {
        AtomicInteger calls = new AtomicInteger();
        var session = new ToolSession(calls::incrementAndGet, (event, detail) -> {});
        for (int i = 0; i < 8; i++) session.reserveRequest();
        assertThrows(SecurityException.class, session::reserveRequest);
        assertEquals(8, calls.get());
        var exhaustedRun = new ToolSession(() -> { throw new SecurityException("run budget"); }, (event, detail) -> {});
        assertThrows(SecurityException.class, exhaustedRun::reserveRequest);
    }

    @Test void auditsHashesAndOutcomesWithoutLeakingArgumentsOrProviderErrors() throws Exception {
        var events = new ArrayList<String>();
        var session = new ToolSession(() -> {}, (event, detail) -> events.add(event + ":" + detail));
        assertTrue(session.invoke("read_file", "private-input", () -> "output").contains("\noutput\n"));
        String failure = session.invoke("search_web", "query", () -> { throw new IllegalStateException("SECRET_SENTINEL"); });
        assertFalse(failure.contains("SECRET_SENTINEL"));
        assertTrue(session.summary().contains("search_web ERROR"));
        assertFalse(events.toString().contains("private-input"));
        assertTrue(events.getFirst().contains("inputSha256="));
        assertTrue(events.getLast().contains("outputSha256="));
        for (int i = 2; i < 12; i++) session.invoke("current_time", "", () -> "time");
        assertThrows(SecurityException.class, () -> session.invoke("current_time", "", () -> "time"));
    }

    @Test void searchCannotConsumeTheFinalResponseRequest() {
        AtomicInteger reservations = new AtomicInteger();
        var session = new ToolSession(reservations::incrementAndGet, (event, detail) -> {});
        for (int i = 0; i < 6; i++) session.reserveRequest();
        session.reserveSearchRequest();
        assertEquals(7, reservations.get());
        assertThrows(IllegalStateException.class, session::reserveSearchRequest);
        assertEquals(7, reservations.get());
        session.reserveRequest();
        assertFalse(session.toolsAllowed());
        assertEquals(8, reservations.get());
        assertThrows(SecurityException.class, session::reserveRequest);
    }

    @Test void toolLimitAlsoSwitchesTheModelToFinalResponse() throws Exception {
        var session = new ToolSession(() -> {}, (event, detail) -> {});
        for (int i = 0; i < ToolSession.MAX_TOOL_CALLS; i++) session.invoke("read_file", "path", () -> "source");
        session.reserveRequest();
        assertFalse(session.toolsAllowed());
    }
}
