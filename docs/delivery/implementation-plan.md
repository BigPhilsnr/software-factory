# Agentic URL Shortener

A Java URL shortener built and evolved through a governed, AI-assisted engineering workflow over a 2–3 day interview assignment.

**Status: design target and assignment interpretation.** The runnable implementation and verified local commands are in the root [README](../../README.md); agent-system evidence and remaining limits are in [agent-system evidence](../architecture/agent-system.md). This document preserves the full target design, including stretch gates that the local prototype does not yet satisfy. Its checklist is a submission gate, not a statement that every item has been completed.

## Assignment interpretation

Section 2 defines the concrete project: build a URL shortener from scratch with core APIs, analytics, and reliability features, then improve it using AI assistance while demonstrating engineering judgment. Sections 1 and 4 define the engineering method: an agentic workflow that understands requirements, decomposes work, implements changes, validates outputs, and produces a reviewable engineering result under human oversight.

The submission therefore has two cooperating parts:

| Part | Responsibility | Evidence of success |
| --- | --- | --- |
| URL shortener | The working product: short-link creation, redirects, analytics, and reliable behavior | Runnable service, API/schema definitions, passing tests, and versioned improvements |
| Agentic engineering workflow | The mechanism used to build and evolve that product | Requirement-specific DAGs, actual code changes, validation, approvals, recovery, re-planning, and audit records |

The engine is built/configured once and reused across greenfield, brownfield, and ambiguous scenarios. Its core is stabilized after the first vertical slice; necessary defect fixes remain allowed and recorded. A framework demonstration without a working shortener is insufficient. Manually building a shortener with occasional AI suggestions also does not establish the required orchestration behavior.

The assignment does not prescribe Java, Google ADK, Redis, endpoint names, a UI, or a particular number of agents. Those are implementation choices. All eight evaluation criteria require concrete evidence; the assignment supplies no numerical weights or guaranteed passing threshold.

## Scope and engineering decisions

| Decision | Rationale and boundary |
| --- | --- |
| Java 21, Spring Boot, Maven | Use one language and conventional modular application structure; pin compatible versions after a build smoke test |
| Google ADK Java behind `AgentRuntime` | Reuse agent/model/tool execution primitives while keeping governance application-owned |
| Claude reasoning and a bounded Claude Code worker | Accelerate repository work; verify model/tool/sandbox integration before committing the schedule to it |
| PostgreSQL for links and basic analytics | Durable source of truth; avoid adding a cache before a measured need |
| Separate control-plane database and credentials | Workers must not modify run state, approvals, or audit records |
| Optional Redis in the reliability scenario | Introduce only if clarified performance requirements justify the added dependency |
| One orchestrator process, at most two concurrent workers | Enough to demonstrate dependency scheduling and synchronization within the timebox |
| Operator CLI and file-based evidence reports | Make approvals and results reviewable without building a dashboard |
| Logical agent roles sharing runtime infrastructure | Separate responsibilities without requiring nine separately deployed services |

Defer UI, Kubernetes, Kafka, multiple model providers, distributed orchestration, geographic/device analytics, enterprise IAM, and production deployment. Do not defer any mandatory orchestration behavior. Timebox the preferred coding-worker integration; the delivery plan below defines a simpler patch-generation fallback that preserves real AI execution and the same governance controls.

## Target application

### API and data contract

Use `/api/shorten` consistently in code, OpenAPI, tests, and examples. This updates the earlier plan's `/api/urls` creation route; no implementation currently exists to migrate.

| Endpoint | Planned behavior |
| --- | --- |
| `POST /api/shorten` | Accept `url`; return `201` with `code` and `shortUrl`. Brownfield adds optional `alias` |
| `GET /{code}` | Return `302` with the stored URL in `Location`; return `404` for an unknown code |
| `GET /api/urls/{code}/analytics` | Return aggregate successful-redirect count; unknown code returns `404` |
| `/actuator/health/liveness` | Report process liveness |
| `/actuator/health/readiness` | Report ability to serve the agreed workload, including required database connectivity |

Planned schema: `links(id, code UNIQUE, target_url, created_at)` and `link_stats(link_id PRIMARY KEY, redirect_count, last_redirect_at)`, managed by versioned Flyway migrations. Use atomic counter updates rather than application-side read/modify/write. Timestamps use UTC. The schema and index choices must be reviewed against the final API and query patterns.

The following limits are prototype design choices, not assignment requirements. Record them in the approved OpenAPI contract and configuration before generating implementation tasks.

Baseline acceptance criteria:

- Accept absolute HTTP/HTTPS URLs with a host and a maximum of 2,048 UTF-8 bytes in the decoded `url` field. Reject credentials, control characters, unsupported schemes, malformed input, and longer URLs with `400`; test the exact boundary.
- Generate eight-character lowercase base36 codes using a secure random source. Generated codes and aliases share one unique database namespace and exclude reserved route names. Allow one initial insert and at most three collision retries, then return a controlled `503`; make collisions injectable in tests.
- Preserve the submitted destination's path and query in redirects. Do not fetch destination URLs server-side or log complete destination query strings.
- Define analytics as redirect responses selected by this service, not proof that the client received them or the destination loaded. Update counts atomically. A counting failure must not turn a valid redirect into an error; record failed/timed-out updates and document uncertain outcomes and possible undercounting.
- Under healthy storage, an isolated test issuing N redirects observes an increase of N. Concurrent redirects must not lose successful counter updates. If asynchronous counting is later approved, version the contract to define its observation deadline and queue-loss semantics.
- Bound the baseline synchronous analytics attempt to 100 ms, including connection acquisition. Keep its transaction and resource budget separate from link lookup so a failed or stalled counter write cannot exhaust redirect capacity. Skip the update on timeout; do not start unlimited background retries.
- Return bounded, sanitized errors when storage is unavailable; do not claim database retries make an outage transparent. Do not automatically retry ambiguous create outcomes without an idempotency design.
- Existing links survive application restarts. Logs include correlation IDs without secrets or unnecessary personal data.

Brownfield acceptance criteria:

- Normalize aliases to lowercase; allow 4–32 ASCII letters, digits, or hyphens. Reject reserved names such as `api` and `actuator`; return `409` for conflicts.
- Concurrent requests for the same alias produce exactly one successful creation; losers receive `409`, not an unhandled database exception.
- Limit creation requests to a configurable default of 30 per source IP in each fixed 60-second UTC epoch window. Count every creation request admitted to the endpoint, including requests that later fail validation; rejected requests do not extend the window. From the 31st request, return `429` with `Retry-After` equal to the positive number of whole seconds rounded up until reset. Redirects remain unaffected. Test concurrent increments and reset boundaries with a controllable clock; document the possible burst across adjacent windows.
- Use the socket peer address in the local demo. Do not trust arbitrary forwarded headers; document proxy configuration as deployment work.
- An in-memory limiter is explicitly single-instance. Scenario 3 must revisit it if the approved workload requires multiple instances.

Expiration and richer analytics are optional extensions, not assumed assignment requirements. Public abuse prevention and analytics authorization require further hardening before an internet deployment; the prototype is local and uses synthetic data.

## Architecture and ownership

```text
Requirement + human operator
            |
            v
Java SDLC control plane -----> control database + artifact registry + audit
  graph / gates / policy
  approvals / recovery
            |
            v
Google ADK via AgentRuntime
            |
      bounded task tools
            |
            v
Isolated implementation and test workspaces
            |
      controlled integration
            |
            v
Trusted validation harness -----> runnable URL shortener + target database
            |
            v
Reviewable diff + evidence + human release-readiness decision
```

ADK owns model invocation, agent context, and tool plumbing. The control plane owns legal transitions, task readiness, artifact versions, approvals, budgets, recovery, and release decisions. ADK session history is not authoritative governance state.

Logical roles: requirement analyst, codebase reasoner, architect/risk reviewer, planner, implementer, independent test author, documentation author, and release summarizer. Roles may share adapters and schemas. Model recommendations never override deterministic policy or approve their own work.

## Governed execution model

The outer lifecycle is explicit:

```text
Understand -> coarse plan -> codebase reasoning -> architecture/risk
  -> design gate -> detailed task DAG -> execute -> validate -> docs
  -> release readiness -> human review
```

Greenfield skips analysis of pre-existing application code. Brownfield must inspect the actual baseline before proposing exact affected files. The lifecycle includes executable backward transitions: failed validation returns to bounded repair; changed context returns to the affected analysis/planning stages; prohibited actions terminate in safe-stop. It must not be implemented as an unconditional linear chain.

The planner generates a requirement-specific inner DAG. Each task records its ID, dependencies, acceptance criteria, expected/new files, input artifact versions, write scope, risk, budget, and measurable exit checks. Validate unique IDs, existing dependencies, acyclicity, and freshness before dispatch.

Implementation and independent acceptance-test authoring may run concurrently from an approved contract. Integration waits for both successful current-version outputs. It merges into a clean candidate workspace and revalidates the combined result; worker success alone is not integration success.

Authoritative run state includes stage, status, task attempts, baseline commit, artifact hashes/versions, approvals, checkpoints, budgets, failure context, and event references. Use transactional state transitions and unique attempt IDs. Terminal outcomes are `COMPLETED`, `FAILED`, `SAFE_STOPPED`, `NOT_APPROVED`, and `CANCELLED`; waiting for an operator is `PAUSED`.

### Entry and exit gates

| Gate | Required condition |
| --- | --- |
| Global entry | Current inputs, known baseline, remaining budget, healthy required dependencies, valid scope, and no outstanding approval |
| Requirements | Valid schema, measurable unique acceptance criteria, explicit assumptions, no unresolved high-impact ambiguity |
| Codebase/design | Impacted modules/API/data flows identified; contracts parse; risks classified; relevant decisions recorded |
| Plan | Valid DAG, complete acceptance-criterion mapping, versioned inputs, scopes, budgets, and exit checks |
| Implementation | Candidate builds; changes stay in approved scope; no secret leakage or protected-test weakening |
| Validation | Required tests actually execute and pass against the candidate revision; approved API, schema, compatibility, and policy checks pass |
| Documentation | README, API guidance, runbooks, and decisions reference current validated artifacts |
| Release | No required stale/failed work; evidence, limitations, metrics, and rollback posture complete; authorized human reviews the exact candidate |

### Human oversight and policy

- **A0:** automatic reads and planning within budget.
- **A1:** automatic reversible edits and tests inside approved isolated workspaces, excluding A2 and prohibited actions.
- **A2:** operator approval before applying dependency, migration, security configuration, breaking-contract, or sensitive-analytics changes; expanding scope; or accepting final release readiness.
- **Prohibited:** production deployment/data access, destructive migrations, host Docker socket access, secret extraction, disabling gates/audit, and agent self-approval.

The control plane classifies proposed actions before execution. A model may raise risk, not lower the deterministic risk floor. Workers may draft high-impact changes as inert patch artifacts for review. Drafting does not authorize applying the patch, resolving new dependencies, running migrations, or executing changed build/security configuration. Denied attempts to cross these boundaries trigger safe-stop; an unapproved proposed patch can remain pending without being executed.

Keep three authorization decisions distinct:

| Authorization | What it permits | What invalidates it |
| --- | --- | --- |
| Design/scope approval | A1 work and preparation of A2 proposals under the approved contract and write scope | Changed requirements, risk classification, contract, or scope |
| A2 action approval | Applying an exact proposed patch and performing its explicitly listed follow-up actions against the approved baseline/environment | Changed patch, baseline, action list, environment, or governing inputs |
| Final release-readiness approval | Accepting the exact integrated candidate with its current validation evidence | Changed candidate, governing inputs, or validation evidence |

The A2 sequence is **propose inert patch → inspect/classify → human approval → verify hash and baseline → apply → validate**. Record the run, authorized operator, action, scope, artifact hashes, and decision at each approval. The executor checks these bindings immediately before acting and records action IDs/results so recovery cannot repeat an already completed action blindly. Ordinary A1 edits within scope do not require approval of each diff; final release review still covers their combined result.

Workers cannot access the operator authorization channel. Merely checking that the approver's name differs from an agent's name is insufficient. A protected-file edit discovered after a bypass attempt is quarantined and treated as a policy violation, not retroactively authorized.

### Re-planning and concurrent work

On an upstream requirement, API, design, risk, or impact-map change:

1. Version the changed artifact and compute affected consumers and descendants.
2. Stop dispatching affected work; cancel or quarantine running affected attempts.
3. Mark affected outputs stale and invalidate approvals bound to superseded content.
4. Reject late results from obsolete versions using attempt/version checks.
5. Preserve unrelated valid completed work; regenerate only the affected subgraph.
6. Revalidate the graph and gates; obtain fresh approval where required before resuming.

Record the trigger, old/new versions, stale set, preserved set, and decision rationale. A required concurrency test changes an input while workers are active and proves that obsolete results cannot be integrated.

### Recovery, rollback, and safe-stop

Default prototype limits are two total attempts per retryable task, a ten-minute worker timeout, and at most two concurrent workers. Before a run, the operator must also configure model-call and token/cost ceilings supported by the chosen adapters. Enforce limits across nested coding-worker calls; missing usage visibility must be documented rather than presented as exact cost enforcement.

| Failure | Route and evidence |
| --- | --- |
| Transient provider/tool failure | One retry after bounded backoff, only if the action is safe to repeat; record both attempts |
| Invalid planner output twice | Propose a conservative dependency-ordered fallback from approved criteria/context; operator must approve the degraded path |
| Regression or failed implementation | Quarantine candidate, restore the isolated code checkpoint, retry within budget with diagnostics |
| Impact-map omission within otherwise authorized write scope | Quarantine candidate, revise impact analysis, and partially re-plan before integration |
| Proposed scope expansion | Pause before the new action; revise plan and obtain approval for the expanded scope |
| Prohibited action, approval bypass, attempted write outside enforced scope, or exhausted global budget | Stop dispatch, terminate/quarantine active workers, revoke worker access, preserve evidence, enter `SAFE_STOPPED` |
| Repeated unsuccessful repair | Enter `FAILED` with diagnostics and a human handoff |
| Rejected approval | Enter `NOT_APPROVED`; no agent bypass |

Recoverable planning omissions concern the accuracy of approved task context; they do not grant additional tool permissions. An attempt to cross an enforced boundary is a policy violation even if the operating system blocks it. The injected brownfield impact-map omission must therefore use a file already allowed by the worker's write scope.

Git rollback applies to code only. Tests and migrations run against disposable, run-scoped target databases; cleanup/recreation is explicit. Committed target data is not claimed to be reversed by resetting Git. Production data mutation is outside scope.

Persist an attempt record before dispatch. On restart, reconcile workspace state, results, and external effects before retrying. Never blindly repeat an action whose outcome is uncertain. Prove recovery by stopping the orchestrator after worker completion but before completion is recorded: restart must preserve evidence and avoid duplicate integration.

## Validation and execution boundaries

- Workers have isolated write scopes and no access to governance storage, operator credentials, host Docker socket, or provider secrets. Validate the chosen model gateway/run-token approach during the integration spike; the fallback keeps model calls in the control plane and needs no provider access from workers.
- Restrict worker network access to approved runtime/service dependencies. Prefetch approved build dependencies; dependency additions require review.
- Repository text and tool output are untrusted task data, not authority to change policy. Include a malicious repository-instruction fixture that attempts to bypass approval or expose secrets; policy must still deny the action.
- Keep independent acceptance tests and their runner outside implementation-worker write access. Mount protected fixtures read-only where needed. A separate Git worktree alone is not a security boundary.
- Treat candidate Maven plugins, migrations, application code, generated tests, and all subprocesses as untrusted execution. Run them in restricted containers with bounded CPU/memory/time, scoped network access, and disposable target databases. They receive no governance credentials, host Docker socket, or write access to authoritative evidence.
- A trusted external supervisor provisions containers and owns test selection, fixture hashes, candidate identity, and result collection. Run independent HTTP acceptance tests in a separate harness against the isolated candidate service; do not execute candidate build scripts in the supervisor process. Candidate-produced unit-test reports are supporting evidence, not a substitute for independent acceptance results.
- The trusted harness records discovered/executed/skipped acceptance-test counts and binds results to the candidate revision. Prove isolation with a candidate build fixture that attempts to overwrite a protected test and access a dummy governance credential: access must fail and the supervisor must reject the run.
- Protected tests retain exact hashes unless an approved contract change authorizes a versioned update. Hash values are compared for equality, never numerical increase/decrease. Test count alone does not prove quality.
- Run unit, PostgreSQL integration, HTTP acceptance, concurrency, compatibility, migration, secret/configuration, and orchestration-control checks appropriate to each change. Choose and document actual scanner thresholds before using them as gates.
- The bug-fix regression must fail for the intended defect on the buggy baseline, then pass after the fix while prior required tests remain green.

Analytics failure acceptance: keep link lookup healthy and inject both an immediate counter-write error and a counter-write stall. In each case, 20 requests for a known link must return `302` with the correct `Location`; an unknown code still returns `404`. On the documented local test machine, require p95 redirect latency below 500 ms, observe the update-failure metric, and verify that pending writes and occupied analytics connections remain bounded after repeated faults. Restore counting and prove subsequent healthy requests increment the count again. These are fault-test thresholds, distinct from Scenario C's normal-load target.

## Three required scenarios

### A. Greenfield: build the URL shortener

**Input:** “Build a URL shortener service with basic analytics.”

Normalize the request, clarify analytics semantics, produce the API/schema and DAG, run implementation and test authoring in parallel, integrate, validate, document, and request human review. Start from an empty application module; a preinstalled approved build harness is allowed and disclosed.

**Successful outcome:** runnable `url-v1` with creation, redirects, aggregate analytics, validation, health endpoints, tests, and setup instructions. Evidence includes the actual raw input, generated tasks, worker changes, parallel/join timestamps, reports, and approval.

### B. Brownfield: enhance, refactor, and fix

**Input:** “Add custom aliases and creation rate limiting to the existing service.”

Inspect `url-v1`, identify impacted controllers/services/storage/contracts/tests, record compatibility invariants, and generate the change DAG. Add a small behavior-preserving refactor where justified. Demonstrate one explicitly labeled injected impact-map omission within authorized write scope, causing quarantine and selective re-planning; do not claim it was a naturally discovered miss or weaken sandbox permissions to demonstrate it.

**Successful outcome:** validated `url-v2`; existing links and clients continue working.

Then introduce a disclosed realistic defect, such as mistakenly applying creation throttling to redirects. Give the agent only the symptom. Require diagnosis, a failing regression test, a fix, prior-test preservation, and a runbook update.

**Successful bug-fix outcome:** `url-v2-fixed` with reproducible red/green evidence. The human reviewer is told the defect was seeded.

### C. Ambiguous: improve reliability under traffic

**Input:** “Make the URL shortener highly reliable and scale for heavy traffic.”

Pause for material missing requirements: target traffic, latency, acceptable errors and analytics loss, dependency failure behavior, deployment topology, and cost constraints. Record the operator's answers as approved acceptance criteria. Change one approved upstream assumption during execution to demonstrate governed re-planning and stale-result rejection.

Proposed local benchmark for operator agreement: 100 redirect requests/second for 60 seconds after a 15-second warm-up, p95 below 200 ms and error rate below 1%, using a documented machine/container configuration and dataset. These are proposed test parameters, not assignment requirements or achieved results. Adjust before approval if the environment cannot support meaningful measurement.

If caching is selected, demonstrate correct database-backed redirects with Redis unavailable. Bound dependency timeouts and measure degraded behavior separately. A database outage may return controlled `503` responses under the approved contract. If scale-out is selected, test multiple service instances and shared limiter/analytics semantics.

**Successful outcome:** validated `url-v3` implementing the approved reliability changes, with measured load/fault results and updated documentation. A short local benchmark does not prove a long-term availability SLO.

Each scenario must show decomposition, orchestration, validation, and a successful reviewable engineering outcome. Negative runs prove controls separately; they do not replace successful scenarios.

## 2–3 day execution plan

Timebox the initial integration spike to **two hours total**. During the first hour, attempt the preferred ADK → Claude Code → isolated worker path. It must produce a real bounded edit, run candidate code in isolation, collect independent validation, and enforce a denied action without exposing provider secrets.

If that path is not proven by the end of the first hour, spend the second hour on the predefined fallback: retain ADK for reasoning, have a control-plane model call produce a structured patch artifact, then use deterministic patch-application and build tools inside the sandbox. Supply repository context through scoped reads and feed validation failures into bounded repair attempts. This removes the Claude Code gateway dependency while retaining real AI-generated work, approval gates, parallel test authoring, and the same audit/state model.

Record the selected adapter, pinned dependencies, passing smoke-test evidence, and trade-off in an ADR. If neither path passes by the two-hour checkpoint, mark the integration milestone blocked, revise the delivery estimate, and report the shortfall. Never silently replace live AI work with replay or relax isolation to keep the original schedule.

| Time | Work | Exit evidence |
| --- | --- | --- |
| Day 1, first two hours | Run integration spike; select and pin the preferred or fallback adapter; prove isolated build/test execution | Real bounded AI edit, independently verified result, denial proof, and ADR |
| Day 1, remainder | Build narrow vertical slice, durable state, task DAG, gates, approvals, parallel join; run greenfield | Runnable `url-v1` with trace and review |
| Day 2, first block | Finish recovery/re-planning/control tests; brownfield enhancement/refactor | `url-v2`, compatibility and stale-work evidence |
| Day 2, remainder | Bug-fix sub-run; ambiguous clarification and approved reliability work | Red/green proof, runbook change, scenario C progress |
| Day 3 | Finish C, load/fault checks, clean-checkout verification, evidence and demo rehearsal | `url-v3`, three successful scenario bundles, complete submission gate |

For a two-day run, reduce feature breadth and measurement sophistication: keep aggregate analytics, one limiter, one meaningful reliability improvement, a CLI, and small fixtures. Preserve all three scenarios and mandatory orchestration controls. If those do not finish, report the prototype as incomplete rather than checking off prose coverage.

## Repository and setup deliverables

The implementation uses the following structure. The external acceptance script and local Compose file stand in for the originally proposed separate `acceptance-tests/` and `infra/` directories:

```text
factory/          # ADK adapter, DAG/state, policy, approvals, recovery
shortener/             # Product API, domain logic, persistence
scripts/               # Independent HTTP and agent replay checks
scenarios/             # A/B/C inputs, operator answers, fault fixtures
compose.yaml            # Local PostgreSQL services
docs/                  # Architecture, ADRs, threat boundaries, runbooks
evidence/<run-id>/      # Generated versioned evidence (ignored by Git)
```

The root README contains the tested local launch commands. The project requires an installed Maven rather than a Maven wrapper. Live model access is optional for fixture replay and remains unverified without an Anthropic key.

Setup acceptance requires a clean-checkout run on the documented environment, with prerequisite checks, no committed secrets, and explicit provider/network/cost requirements. Offline replay must be separately documented; it must never silently substitute for a requested live model run.

## Evidence, metrics, and replay

Every run records raw/normalized requirements, acceptance criteria, assumptions, impact/design/risk artifacts, the detailed DAG, artifact versions, baseline/candidate commits, diffs, decisions, approvals, attempt/checkpoint records, validation reports, metrics, and a final engineering summary.

Events carry run/stage/task/attempt IDs, actor, timestamps, input/output references, status, and decision rationale references. Store concise decision justifications, not private model reasoning. Redact secrets and unnecessary personal data.

Use append-only events outside worker access with a hash chain. Finalize events and payload files, create a manifest that excludes itself and the final summary, then write the summary referencing the event root and manifest hash. Anchor the summary hash in the reviewed tag. This avoids circular hashing; local anchoring is tamper-evident relative to the trusted root, not externally immutable storage.

| Metric | Definition |
| --- | --- |
| Pipeline success rate | Completed runs / all terminal runs; protective stops are reported separately |
| Injected outcome correctness | Injected runs reaching their expected outcome / injected runs |
| Retry frequency | Additional attempts / distinct executed tasks; also report runs with retries |
| Rollback frequency | Rollbacks / implementation attempts |
| MTTR | Mean time from each recoverable incident's first failure to restored passing validation; report unrecovered incidents separately |
| Wall latency | Terminal timestamp minus run start, including human wait |
| Active latency | Wall latency minus recorded human-wait intervals |
| Usage/cost | Measured provider usage where available; label estimates and unknowns |

Report sample sizes, zero-denominator values as N/A, and separate real-model, replay, and injected runs. Small samples demonstrate instrumentation, not production reliability.

Replay re-feeds recorded model responses and worker diffs through the actual control plane. Gates, policy, artifact checks, transitions, and metrics execute again; replay never supplies live authorization for new actions. Preserve historical approvals as historical evidence and require current operator authorization for replay actions that need it. Include at least one recorded successful real-model run for each scenario; replay alone does not establish AI-generated engineering execution.

## Evaluation and submission gate

| Assignment criterion | Required review evidence |
| --- | --- |
| Effectiveness of orchestration | Actual DAG, parallel join, approval pause/resume, recovery, and selective re-planning |
| Architecture/system design | Component/control-flow overview, trust boundaries, state ownership, and ADRs |
| Decomposition/execution depth | Scenario-specific tasks linked to criteria and actual changes |
| Realism/quality of outputs | Runnable shortener, OpenAPI/schema, tests, documentation, and version history |
| Validation/risk rigor | Independent checks plus negative tests that block unsafe progression |
| Clarity/defensibility | Recorded alternatives, assumptions, human corrections, and trade-offs |
| Core engineering principles | Modular design, concurrency/failure tests, security controls, measured scaling behavior, and safe change evidence |
| Engineering judgment | Timebox decisions, justified reuse, rejected AI output, ownership, and honest limitations |

- [ ] Clean checkout starts using verified setup instructions.
- [ ] Greenfield, brownfield, and ambiguous scenarios each succeed end-to-end.
- [ ] The bug-fix sub-run includes genuine defect-specific red/green evidence.
- [ ] Real AI runs are distinguishable from replay and injected failures.
- [ ] Parallel workers and synchronization are visible in execution traces.
- [ ] Upstream changes invalidate affected work and reject late stale results.
- [ ] Human approval binds to the actual candidate and cannot be forged/reused by workers.
- [ ] A2 patches remain inert until exact-patch approval; changed patches/baselines fail authorization checks.
- [ ] Retry exhaustion, fallback, rollback, rejected approval, and safe-stop are reproducible.
- [ ] Restart recovery avoids duplicate integration and preserves evidence.
- [ ] Independent tests execute against the actual candidate; bypass attempts fail.
- [ ] Candidate build plugins and subprocesses cannot modify protected tests or access governance credentials.
- [ ] Core API, analytics, concurrency, and dependency-failure behavior are verified.
- [ ] URL/code limits and limiter boundaries pass; analytics failure and stall tests preserve bounded redirects.
- [ ] Metrics are derived from events; evidence integrity is checkable.
- [ ] Architecture, setup, testing approach, limitations, trade-offs, and final summaries are complete.
- [ ] A human reviews the final diff and release-readiness evidence.

## Human ownership and final summary

Maintain an AI usage and decision log: tool/model, assigned task, produced artifact, checks performed, accepted or rejected suggestion, human correction, and rationale. Include concrete examples such as rejecting an unsafe migration, correcting rate-limit scope, or requiring an additional concurrency test. Do not fabricate interventions merely to fill the log.

Each final summary states the requirement/outcome, plan/rationale, baseline and candidate, changed artifacts, validations, approvals, risks/trade-offs, assumptions, limitations, recovery posture, measured metrics, AI contributions, human decisions, and release-readiness verdict.

This is a production-minded prototype, not a claim of production readiness. Enterprise identity, externally anchored audit retention, high availability/disaster recovery, public abuse controls, and sustained operational validation remain deployment work. The submission succeeds through working behavior and inspectable evidence, not through the number of planned controls.

## Source documents

- `Assignment Agentic-Proficient Software Engineer.pdf`: authoritative assignment; Sections 1–2 define objective/scenario, Section 4 defines capabilities, and Sections 5–6 define deliverables/evaluation.
- `Google_ADK_Java_Agentic_URL_Shortener_Plan.pdf`, Revision 5: original design input. This README updates its scope, completion rules, approval semantics, recovery/re-planning behavior, test integrity, and evidence requirements.

The assignment is marked “Schwab Internal.” Source documents are not copied into this repository; use authorized review channels and synthetic demo data.
