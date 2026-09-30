# Agent system: implementation and validation

The control plane is a single Java 21 process backed by a separate PostgreSQL database. `RunEngine` owns task readiness and transitions; `TaskGraph` validates dependencies and lifecycle order; `ControlRepository` persists state with an append-only SHA-256 audit chain and a per-run advisory lock. `EvidenceStore` keeps versioned outputs outside candidates. `GitWorkspace` creates detached worktrees from pinned baseline commits and checks patch paths and scope. `SandboxValidator` runs candidate Maven tests with no network, no governance credentials, a read-only host dependency cache, a read-only container filesystem, and CPU/memory/process limits.

Fixture mode reads recorded artifacts from `scenarios/`. Live mode uses `AdkClaudeRuntime` through Google ADK and sends bounded repository source context to Claude; it requires an Anthropic key. The Claude adapter strips private thinking blocks from responses while preserving visible text, because ADK 1.10.1 does not handle the thinking blocks returned by Claude Sonnet 5. The adapter emits patch text for deterministic scope checks and application. There is no Claude Code subprocess or autonomous filesystem tool in the fallback path.

The four scenario DAGs are deliberately different. Greenfield starts from `project-start`; brownfield inspects `url-v1`; ambiguity blocks on operator clarification and can selectively invalidate descendants; the seeded bug-fix run adds a regression test, verifies one expected failure on `url-v2-buggy`, applies the repair, and verifies the full suite. At most two ready artifact tasks run concurrently, then downstream tasks wait for both. `PATCH` tasks cannot write outside their declared paths. A deterministic policy requires approval for Maven files, migrations, Compose and application configuration, security configuration, control-plane code, or any scenario-marked A2 patch. Release always requires a separate hash-bound approval of the validated diff.

Approvals bind patch bytes, pinned baseline commit, scenario/requirement hashes, and current upstream artifact hashes. A changed clarification therefore creates a different approval hash. Release also checks that the candidate diff still matches the one validated in the restricted container. A pending approval cannot advance or regenerate itself. A rejected approval is terminal. This local CLI records an operator label but does not provide identity proof; production use would need an authenticated operator channel and stronger separation of credentials.

On restart, a per-run lock prevents a second operator process from advancing the same run. Interrupted artifact output is reconciled from immutable evidence. An interrupted patch causes the isolated candidate to reset to its pinned baseline, replays completed patches, and then uses the saved proposal; validation is rerun. Malformed patch proposals are saved as evidence and pause for a bounded retry; an out-of-scope patch enters `SAFE_STOPPED` immediately. Other retryable failures pause once and become `FAILED` on the second unsuccessful attempt. Red validation derives the generated Java test class from the test patch, then requires exactly one assertion failure without test errors or skips. Live runs enforce a configured model-call count ceiling, but provider token or dollar ceilings are not measurable through this adapter.

## Local evidence checked

The reproducible command is `python3 scripts/agent_smoke.py` after starting `control-db`, warming Maven's cache, and pulling the validator image as shown in the root README. A local run verified:

| Case | Observed result |
| --- | --- |
| Greenfield fixture | `COMPLETED`; 3 sandboxed candidate tests passed; audit valid |
| Brownfield fixture | `COMPLETED`; candidate tests passed; audit valid |
| Ambiguous fixture | Paused for clarification, invalidated downstream work, required a new patch hash, then `COMPLETED`; audit valid |
| Seeded bug-fix fixture | One regression test failed at redirect 30 (`302` expected, `429` actual); after repair 5 tests passed; audit valid |
| Out-of-scope patch | `SAFE_STOPPED` before application |
| Invalid in-scope patch | `PAUSED` after one attempt, `FAILED` after two; diagnostic artifacts retained |
| Wrong approval hash | Rejected without changing run state |
| Rejected release | `NOT_APPROVED` remains terminal |
| Simulated validation and patch interruption | Recovery returned to release review, with the candidate diff applied once and a valid audit chain |

Fixture completion uses the explicitly labeled `synthetic-fixture-test` operator. These results show orchestration mechanics and deterministic replay; they do not claim independent human sign-off.

A live run of `scenarios/bugfix.json` (`d44416f4-8d94-4d6a-9671-1c54ffd9f15f`) used four model calls and reached `PAUSED` at release approval. Its generated regression failed at visit 31 with `429` instead of `302`; its generated repair removed the redirect limiter while preserving creation throttling; five candidate tests passed, and `verify-audit` returned `AUDIT_VALID`. The proposed candidate diff contains one production-line deletion and one new 45-line regression test. The release hash is `a4ba50caa030fb3dce329dfcadfdb0d05bad6f2efdc4ede55d96122c7ec6c979`; no human release approval has been recorded.

A separate live greenfield trial produced requirements, architecture, risk, plan, and independent-test artifacts but exposed an unconstrained-stack problem: from an empty baseline, the model chose Node/TypeScript and returned a patch outside the intended Java project. That trial does not establish live greenfield completion. The product HTTP acceptance script runs separately against the local service; the orchestrator currently gates on candidate Maven tests, not that external HTTP script. Scenario C's 100 requests/second and p95 target are proposed acceptance parameters, not measured throughput results. The control plane is a local prototype; it lacks enterprise authentication, remote audit anchoring, and multi-host scheduling.
