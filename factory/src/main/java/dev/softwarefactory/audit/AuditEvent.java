package dev.softwarefactory.audit;

import java.time.Instant;

/** One link of a run's audit chain, as read back for timelines and metrics. */
public record AuditEvent(long sequence, Instant at, String type, String detail) {}
