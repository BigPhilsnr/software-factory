package dev.shortener.redirect;

/** The hand-off to analytics: told about every followed link, never allowed to slow or fail the redirect. */
@FunctionalInterface
public interface VisitRecorder {
    /** Best-effort and bounded; failure cannot prevent the redirect. */
    void record(long linkId);
}
