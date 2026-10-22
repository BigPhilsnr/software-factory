package dev.softwarefactory.run;

/** Where a run stands. Terminal states refuse ordinary actions; narrow verified recovery transitions are separate. */
public enum RunStatus {
    CREATED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    SAFE_STOPPED,
    NOT_APPROVED;

    /** A terminal run cannot be advanced, approved or revised through ordinary workflow actions. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == SAFE_STOPPED || this == NOT_APPROVED;
    }
}
