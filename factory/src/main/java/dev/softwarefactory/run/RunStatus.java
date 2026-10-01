package dev.softwarefactory.run;

/** Where a run stands. PAUSED always waits for the operator or the platform; terminal states never change. */
public enum RunStatus {
    CREATED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    SAFE_STOPPED,
    NOT_APPROVED;

    /** A terminal run cannot be advanced, approved or revised. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == SAFE_STOPPED || this == NOT_APPROVED;
    }
}
