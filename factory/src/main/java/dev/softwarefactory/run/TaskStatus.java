package dev.softwarefactory.run;

/** Where one task of a run stands. STALE marks work invalidated by a revision before it is re-queued. */
public enum TaskStatus {
    PENDING,
    RUNNING,
    DONE,
    STALE,
    FAILED
}
