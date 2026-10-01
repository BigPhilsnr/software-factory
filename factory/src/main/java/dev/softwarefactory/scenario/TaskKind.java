package dev.softwarefactory.scenario;

/** What a task does; the run engine has one executor per kind. */
public enum TaskKind {
    /** An agent writes a document. */
    ARTIFACT,
    /** An agent proposes a diff that is scope-checked, possibly approved, then applied to the candidate. */
    PATCH,
    /** The candidate's full test suite must pass in the sandbox. */
    VALIDATE,
    /** A new regression test must fail on the still-buggy candidate. */
    VALIDATE_RED,
    /** The operator answers a question before work continues. */
    CLARIFY,
    /** The operator approves the validated candidate diff. */
    RELEASE;

    public boolean isValidation() {
        return this == VALIDATE || this == VALIDATE_RED;
    }
}
