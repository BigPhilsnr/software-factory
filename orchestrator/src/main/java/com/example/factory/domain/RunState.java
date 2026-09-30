package com.example.factory.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Authoritative state is serialized transactionally by the control repository. */
public final class RunState {
    public String id;
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
    public int modelCalls;
    public int maxModelCalls;
    public Instant startedAt;
    public Instant finishedAt;
    public Map<String, TaskStatus> tasks = new HashMap<>();
    public Map<String, String> artifactHashes = new HashMap<>();
    public Map<String, Integer> attempts = new HashMap<>();
    public Map<String, Integer> artifactVersions = new HashMap<>();
    public Map<String, String> approvals = new HashMap<>();
    public Map<String, Integer> patchDrafts = new HashMap<>();
    public Map<String, String> reviewFeedback = new HashMap<>();

    public RunState() {}

    public RunState(String id, String scenario, String requirementHash) {
        this.id = id;
        this.scenario = scenario;
        this.requirementHash = requirementHash;
        this.status = RunStatus.CREATED;
        this.startedAt = Instant.now();
    }
}
