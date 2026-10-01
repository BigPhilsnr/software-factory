package dev.softwarefactory.audit;

/** The vocabulary of run audit events. Names are persisted and hashed, so they never change. */
public final class EventTypes {
    public static final String RUN_CREATED = "RUN_CREATED";
    public static final String RUN_RESUMED = "RUN_RESUMED";
    public static final String RUN_COMPLETED = "RUN_COMPLETED";
    public static final String RUN_FAILED = "RUN_FAILED";
    public static final String RUN_RECOVERING = "RUN_RECOVERING";
    public static final String RUN_RECOVERED = "RUN_RECOVERED";
    public static final String REPLAN_REQUIRED = "REPLAN_REQUIRED";
    public static final String PARTIAL_REPLAN = "PARTIAL_REPLAN";
    public static final String ARTIFACTS_STALE = "ARTIFACTS_STALE";
    public static final String REVIEW_FEEDBACK_RECORDED = "REVIEW_FEEDBACK_RECORDED";
    public static final String TASK_STARTED = "TASK_STARTED";
    public static final String TASK_DONE = "TASK_DONE";
    public static final String TASK_FAILED = "TASK_FAILED";
    public static final String RETRY_AVAILABLE = "RETRY_AVAILABLE";
    public static final String PARALLEL_JOIN = "PARALLEL_JOIN";
    public static final String PATCH_STARTED = "PATCH_STARTED";
    public static final String PATCH_DRAFTED = "PATCH_DRAFTED";
    public static final String VALIDATION_STARTED = "VALIDATION_STARTED";
    public static final String CANDIDATE_VALIDATED = "CANDIDATE_VALIDATED";
    public static final String REVALIDATION_REQUIRED = "REVALIDATION_REQUIRED";
    public static final String REVISION_REQUIRED = "REVISION_REQUIRED";
    public static final String INFRASTRUCTURE_UNAVAILABLE = "INFRASTRUCTURE_UNAVAILABLE";
    public static final String POLICY_SAFE_STOP = "POLICY_SAFE_STOP";
    public static final String APPROVAL_REQUIRED = "APPROVAL_REQUIRED";
    public static final String APPROVAL_GRANTED = "APPROVAL_GRANTED";
    public static final String APPROVAL_REJECTED = "APPROVAL_REJECTED";
    public static final String RELEASE_APPROVED = "RELEASE_APPROVED";
    public static final String CLARIFICATION_REQUIRED = "CLARIFICATION_REQUIRED";
    public static final String CLARIFICATION_RECORDED = "CLARIFICATION_RECORDED";
    public static final String MODEL_CALL_STARTED = "MODEL_CALL_STARTED";
    public static final String MODEL_CALL_RESERVED = "MODEL_CALL_RESERVED";

    private EventTypes() {}
}
