# Final Engineering Summary

This document addresses assignment section 4.8: plan and rationale, artifacts, risks, validation, assumptions and limitations. The prototype combines a runnable URL shortener with an agentic engineering workflow. It is a local interview prototype, not a production deployment or proof that arbitrary requirements can be implemented reliably.

## Plan and rationale

The product provides URL creation, redirects, aggregate analytics and reliability controls. The factory uses it as an engineering workspace: normalize requirements, reason about code, produce architecture/risk artifacts, plan dependencies, propose code and tests, validate an isolated candidate, document the result and request release approval.

Java 21 and Spring Boot keep the product and operator API familiar. Google ADK coordinates model/tool calls; deterministic Java code owns the task graph, approvals, budgets, filesystem scopes and execution. PostgreSQL stores product data and, in a separate database, workflow state and audit events. Git worktrees pin candidates to known commits. These choices favor an inspectable 2–3 day prototype over a distributed orchestration platform. See [architecture](architecture/agent-system.md) and [decisions](architecture/decisions.md).

Agents read bounded source context, search the web, read public HTTPS pages and inspect Git. They propose inert diffs. The control plane applies changes after scope/applicability checks and any required exact-hash approval. Candidate tests run in a container with no network or governance credentials. A model answer cannot grant approval, merge code or deploy the application.

## Delivered artifacts

| Artifact | Entry point | Demonstrated behavior |
| --- | --- | --- |
| URL shortener | [product guide](../shortener/README.md), [API contract](../shortener/openapi.yaml) | Creation, aliases, redirects, aggregate counts, validation, creation throttling, caching, PostgreSQL uniqueness and Flyway migrations |
| Workflow engine | [RunEngine](../factory/src/main/java/dev/softwarefactory/workflow/RunEngine.java), [TaskGraph](../factory/src/main/java/dev/softwarefactory/workflow/TaskGraph.java) | Dependencies, parallel branches/joins, durable state, retries, recovery, approval and invalidation |
| Operator interfaces | [operator guide](operations/operator-guide.md) | Native ADK chat, browser run/evidence/approval controls and CLI |
| Agent tools | [tool guide](operations/agent-tools.md) | Seven bounded read-only tools, provider-budget accounting and tool audit events |
| Scenarios | [scenario guide](../scenarios/README.md) | Greenfield, brownfield, ambiguous, seeded bug-fix and negative-control fixture replays |
| Checks | [scripts guide](../scripts/README.md), [CI workflow](../.github/workflows/verify.yml) | Default tests, DB/container integration, HTTP acceptance, clean-clone checks and evidence checksums |
| Portable evidence | [committed samples](evaluation/samples/README.md) | Historical live bug-fix patches and red/green reports, failed live outcomes and historical evaluation logs |

The three principal scenarios use recorded fixtures by default. They exercise actual orchestration, patch application, validation and approval transitions, but do not demonstrate newly generated AI output. Their test-plan artifacts do not make their bundled executable tests independent. In the ambiguous fixture, the answer is persisted and affects decision lineage; it does not select or rewrite the recorded patch. The newer feature template separates production and executable-test patch tasks, but that is not proof of three successful live scenarios.

## Control flow and gates

These gates are enforced in the state machine and policy/execution collaborators. They are not a generic declarative gate-object framework.

| Transition | Entry condition | Exit condition / stop behavior |
| --- | --- | --- |
| Task dispatch | Valid acyclic graph, legal stage order, completed dependencies, run lease and valid evidence | Started state persisted before work; only ready tasks dispatched |
| Artifact branch / join | Dependencies done; provider budget available for live generation | Versioned artifact/digest persisted; joined branches must succeed before dependents run |
| Clarification | Explicit `CLARIFY` task in the trusted scenario | Nonblank operator answer recorded before dependent work; fixture output remains fixed |
| Patch application | In-scope applicable diff; current approval if policy requires it | Candidate changed only in its worktree; policy violation safe-stops |
| Validation | Patch dependencies complete | Sandboxed Maven succeeds with positive test count and zero failures/errors/skips; otherwise bounded retry/escalation |
| Release | All required work joined, evidence intact, candidate matches validated hash | Exact release hash approved by operator; completion does not merge or deploy |
| Revision / interruption | Explicit revision or interrupted task checkpoint | Affected descendants/approvals invalidated; candidate reset/replayed where needed; validation refreshed |

Topology comes from trusted templates. Upstream changes invalidate and regenerate affected work; the engine does not autonomously invent DAGs or insert clarification tasks from arbitrary prose. Parallelism is capped at two artifact tasks. The operator can resume after one retryable failure; exhaustion fails the run. The fallback is pause/escalation, without an automatic provider switch or silent fixture substitution. Artifact completion alone does not establish semantic quality.

## Validation and evidence

- Default `mvn clean test` needs no PostgreSQL, Docker or paid model key. Six `RunEngineBehaviorTest` cases drive the real engine, evidence files and temporary Git candidates through retry exhaustion, stale/changed approvals, parallel policy failure, evidence tamper, interruption recovery and lease exclusion. Its in-memory store tests engine decisions, not PostgreSQL locking or hash-chain storage.
- The explicit `integration` profile tests real PostgreSQL/HTTP behavior and durable control-plane tamper, locking and interrupted-patch recovery. Python checks replay scenarios and exercise operator controls and the running product. These require the documented services and a warm sandbox dependency cache.
- A historical **live seeded bug-fix** generated a diagnosis, regression test, repair and runbook using four provider calls. Its reports show one expected failing assertion before the fix and five passing tests afterward. [Exact evidence](evaluation/samples/live-bugfix/run.json) is committed with checksums.
- Historical **live greenfield and brownfield trials failed**. Their [outcomes](evaluation/samples/live-failures/outcomes.json) remain visible. Live tool smoke tests prove callable tools, not end-to-end software delivery.
- A packaging defect was reproduced: `47fa6f6` lacked `EvidenceStore.java` because the bare `evidence/` ignore rule hid the source package. `8cb4d73` anchors runtime ignores to the root and tracks the class; a fresh clone then passed all 40 default tests present at that revision. See the [review response](reviews/external-review-response.md) for current reproduction commands.
- CI is configured for committed code and evidence checksums. No remote is configured here, so a hosted CI pass is not claimed. Test approvals are synthetic; recorded local operator labels do not establish authenticated human identity.

The final review verification on code revision `08e8deb` passed 46 tests from a fresh clone, 50 tests with integration enabled, all six scenario replays, operator controls and product HTTP acceptance. [Recorded results](evaluation/samples/review-verification/results.json) include the tested revision; subsequent evidence-only commits do not change that code.

## Risks, trade-offs and assumptions

The operator owns scope and final quality. The prototype assumes one trusted local operator, loopback UI access, a single application instance, JDK 21, Git, Maven, Docker for sandboxed validation and local PostgreSQL for integration. Live generation needs an Anthropic key and provider availability. Separate product/control databases and the restricted candidate container reduce the scope of failures without claiming a complete hostile-code sandbox.

Hashes and the chained audit log detect ordinary tampering, but a privileged host/database owner can rewrite both. Approval identity is local. Call-count budgets bound requests, not dollars/tokens. Web/source text may contain prompt injection; tool restrictions and deterministic governance remain the authority. See the [risk register](evaluation/risks.md).

Analytics are best effort: redirects enqueue without waiting for writes; counts may lag/drop, and the API exposes the last recorded GET timestamp. The bounded cache evicts the least recently used entry and briefly caches missing codes; cache and limiter remain per process. The limiter uses the direct peer address, appropriate for the documented direct local deployment; trusted-proxy handling must be designed before proxy deployment. Link expiry/deletion are absent. The assignment requires reliability features but does not specifically mandate these two features or geographic/device analytics.

## Remaining work

1. Demonstrate bounded live greenfield, brownfield and ambiguous delivery with reviewed code, executable tests and operator decisions. Fixture success cannot replace that evidence.
2. Make independently controlled HTTP acceptance a per-candidate release gate. Today the real DB/HTTP test validates the current checkout; the sandbox validates candidate Maven tests.
3. Improve ambiguity detection and governed plan selection before claiming adaptive topology or alternate execution strategies.
4. Before production use, add authenticated approvals, durable queued analytics if required, appropriate cache eviction, trusted-proxy handling, distributed operational controls and measured load testing.

These limits are part of the engineering judgment. The repository does not establish a guaranteed evaluation score.
