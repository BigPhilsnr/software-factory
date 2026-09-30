# Engineering decisions

## ADK model adapter with deterministic control plane

Google ADK performs live model invocation through `AgentRuntime`; fixture replay supplies recorded outputs through the same task boundary. The Java control plane owns dependencies, state, approvals, policy, and patch application. This keeps authorization out of model responses. The preferred Claude Code worker path was not verified in this environment, so the implemented fallback asks the model for an inert unified diff and applies it only after deterministic checks. A live seeded bug-fix run completed with operator approval; larger greenfield and brownfield trials failed. Those outcomes are recorded in the agent-system guide, so fixture success is not presented as proof of general live model quality.

## Separate durable authority and isolated candidates

Run state and hash-chained audit events live in a dedicated PostgreSQL database. Code candidates are detached Git worktrees pinned to a baseline commit. Worker proposals are versioned evidence, and a per-run advisory lock serializes operator transitions. This is sufficient for a local single-host prototype; the operator CLI is not authenticated and the audit chain is not externally anchored.

## Restricted validation

Candidate Maven tests execute in a container with no network, no Docker socket or governance credentials, bounded CPU/memory/processes, a read-only image filesystem, and a read-only Maven cache. A host-side supervisor selects the test command and counts Surefire results. Candidate unit tests support the result; the separate HTTP acceptance script checks the running product locally. The HTTP script is not yet an orchestrator release gate.

## Product persistence and reliability

PostgreSQL is the source of truth for immutable links and aggregate counts. Alias uniqueness is enforced by the database. The creation limiter and 60-second link cache are in-process because the prototype runs one instance; neither offers distributed semantics. Analytics writes use a separate small pool and bounded queue so a slow counter does not block redirects indefinitely. This favors redirect availability, with possible count lag or loss that must be disclosed.


## Reliability evidence and conservative recovery

Metrics derive from the full audit timeline, with fixture and live aggregates kept separate. A missing recovery sample is null, not a zero MTTR. Interrupted patch recovery invalidates dependent validation, documentation and approvals before proceeding. Integrity failures stop the run before further work. This costs extra validation time but preserves the relationship between the candidate and its evidence.

## Independent product verification

An opt-in HTTP integration test boots the current Spring application against a disposable schema in real local PostgreSQL, exercises concurrent uniqueness, analytics and creation-only throttling, then drops only that schema. This complements isolated candidate tests. It is explicitly not yet a mandatory per-candidate HTTP release gate.
