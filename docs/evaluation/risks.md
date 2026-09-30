# Risk register

Likelihood/impact are qualitative engineering judgments for this local prototype, not measured probabilities.

| Risk | Likelihood / impact | Control and evidence | Residual risk / decision |
| --- | --- | --- | --- |
| Wrong model code or malformed patch | High / high | Small scoped production/test patches, Git preflight, bounded retries with diagnostics, sandbox tests, human review | Model quality is not guaranteed. Pause/escalate to the operator instead of silently substituting a fixture. |
| Stale approval after upstream change | Medium / high | Input/artifact/baseline-bound hashes, descendant invalidation, candidate validation hash | User edits to external files are outside the model boundary; review current evidence. |
| Corrupted evidence | Low / high | Verify complete audit chain and artifact digests before advancing/releasing; tamper fault injection safe-stops | A privileged DB/filesystem owner can rewrite history. External anchoring is deferred. |
| Crash while applying a patch | Medium / high | Reconcile immutable output, reset candidate, replay valid patches, invalidate descendants, rerun validation | Local supervisor/database durability still depend on the host. No multi-host failover claim. |
| Concurrent operators mutate one run | Medium / high | PostgreSQL advisory lease; concurrent-advance test | Browser admission caps two active runs in this process; separate CLI processes still share only per-run locking. |
| Secrets or unrelated code enter a prompt | Medium / high | Product-only bounded source context; no `.env`, build output or symlink traversal; regression tests | Allowlisted source can contain a secret committed by a human. No comprehensive secret scanner is claimed. |
| Malicious candidate build | Low / high | No network or governance credentials, read-only dependency cache/image, CPU/memory/PID caps; symlink/submodule refusal | Containers are not a perfect security boundary; Maven controls its own test reports. High-impact build edits require review. |
| False confidence from tests | Medium / high | Real HTTP/PostgreSQL checks, negative controls, fault injection, explicit fixture/live labels | Per-candidate tests are not independent hostile-code verification; HTTP checks are not yet a release gate. |
| Unbounded cost or resource use | Medium / medium | Per-run model-call ceiling, two-attempt policy, five-minute provider timeout with SDK retries disabled, sandbox time/resource limits, two browser runs at a time | No dollar/token ceiling; conversational calls have a separate documented budget boundary. |
| Analytics slows redirects or loses counts | Medium / medium | Separate small DB pool, bounded queue/wait, failure counter; real HTTP test | Best-effort analytics can lag/drop; counts are not billing-grade. |
| Duplicate aliases under concurrency | Medium / medium | PostgreSQL unique constraint and 409 mapping; concurrent real-DB test | No cross-region database strategy in this prototype. |
| Cache/limiter inconsistency when scaled out | High if multi-instance / medium | Explicit single-instance operating assumption | Distributed cache/rate-limit adapters and measured load tests are required before scaling out. |
| Unauthorized approval | Low on trusted local host / high | Loopback, origin checks, page token, exact-hash commands; model has no approval tools | Local OS user is trusted; enterprise identity/RBAC is deferred. |

Owners: the operator owns scope, assumptions and release decisions; deterministic governance owns enforcement; agents own proposals only. Risk artifacts can raise concerns but cannot lower the enforced approval floor.
