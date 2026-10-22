# ADR 0019: Model-call counts are metrics, not execution limits

**Status:** accepted; supersedes model-call ceiling provisions in ADRs 0011, 0014 and 0018.

## Context

The assignment requires bounded retries, fallback, rollback and safe-stop controls, but prescribes no provider-call count. Small lifetime allowances repeatedly interrupted useful incremental drafting, especially after earlier failed attempts consumed calls. The operator explicitly requested removal of the model-call budget.

## Decision

Remove run, invocation and daily-chat provider-call ceilings. `FACTORY_MAX_MODEL_CALLS` and `FACTORY_CHAT_DAILY_REQUESTS` are no longer read; stale environment values have no effect. Remove the budget-increase API action, chat command and UI button. Resume is not disabled by historical call counts.

Continue counting every model request and auditing before transmission. Preserve `RunState.maxModelCalls` only to deserialize historical snapshots; new runs leave it at zero and no execution path enforces it. The existing `chat_budget` SQL table stores daily usage with no limit predicate. No migration or evidence rewriting is needed.

Keep tool limits (12 for chat/documents, 64 for drafts), invocation deadlines, provider timeouts, one empty/truncated-response recovery attempt, three drafting repair passes/checks, no-progress detection, exact-hash approvals and policy/integrity checks. Reaching the tool limit ends exploration, independently of how many provider calls have occurred. Provider output-token limits are separate and remain in force.

## Consequences

Existing paused runs can continue their saved drafts without increasing an allowance. This does not turn empty responses, invalid code or infrastructure failures into success, and does not bypass terminal policy stops. Model usage remains observable, but there is no count-based or monetary spending ceiling. Explicit Resume still starts paid generation; removing the limit does not itself start a run.

## Validation

Regression tests cover more than 100 counted requests, resuming an old 40/40 paused run, unlimited search request accounting, bounded empty-response recovery, tool-limit finalization and daily chat usage across repository recreation. Existing deadline, policy, approval and checkpoint tests remain in force.

## Revisit when

The operator requests a spending policy; any replacement must be explicit and suited to incremental work rather than silently restoring a small lifetime request counter.
