package dev.softwarefactory.workflow;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative state is serialized transactionally by the control repository. Parallel branches
 * update the maps concurrently, so deserialization must restore concurrent maps, not Jackson's default.
 */
public final class RunState {
    public String id;
    public long revision;
    public String scenario;
    public String specPath;
    public String specHash;
    public String baselineTag;
    public String baselineCommit;
    public String candidatePath;
    public String mode;
    public String requirementHash;
    public RunStatus status;
    public String pendingApprovalTask;
    public String pendingApprovalHash;
    public String pendingClarificationTask;
    public String validatedCandidateHash;
    /** A validation task that failed reproducibly; an upstream task must be revised before advancing. */
    public String revisionRequiredTask;
    public int modelCalls;
    public int maxModelCalls;
    public Instant startedAt;
    public Instant finishedAt;
    @JsonDeserialize(as = ConcurrentHashMap.class) public Map<String, TaskStatus> tasks = new ConcurrentHashMap<>();
    @JsonDeserialize(as = ConcurrentHashMap.class) public Map<String, String> artifactHashes = new ConcurrentHashMap<>();
    /** Failed attempts since the task's inputs last changed; reset when the task is invalidated. */
    @JsonDeserialize(as = ConcurrentHashMap.class) public Map<String, Integer> attempts = new ConcurrentHashMap<>();
    /** Latest diagnostic evidence version per task; never reset, so diagnostic evidence stays immutable. */
    @JsonDeserialize(as = ConcurrentHashMap.class) public Map<String, Integer> diagnosticVersions = new ConcurrentHashMap<>();
    @JsonDeserialize(as = ConcurrentHashMap.class) public Map<String, Integer> artifactVersions = new ConcurrentHashMap<>();
    @JsonDeserialize(as = ConcurrentHashMap.class) public Map<String, String> approvals = new ConcurrentHashMap<>();
    @JsonDeserialize(as = ConcurrentHashMap.class) public Map<String, Integer> patchDrafts = new ConcurrentHashMap<>();
    @JsonDeserialize(as = ConcurrentHashMap.class) public Map<String, String> reviewFeedback = new ConcurrentHashMap<>();

    public RunState() {
        // Jackson and persistence adapters populate fields.
    }

    public RunState(String id, String scenario, String requirementHash) {
        this.id = id;
        this.scenario = scenario;
        this.requirementHash = requirementHash;
        this.status = RunStatus.CREATED;
        this.startedAt = Instant.now();
    }
}
