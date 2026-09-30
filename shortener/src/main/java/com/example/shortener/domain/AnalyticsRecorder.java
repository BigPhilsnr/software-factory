package com.example.shortener.domain;

public interface AnalyticsRecorder {
    /** Best-effort and bounded; failure cannot prevent the redirect. */
    void record(long linkId);
}
