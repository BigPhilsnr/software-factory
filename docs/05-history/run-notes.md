# Run and verification notes

> **Historical document.** These notes were written while the system was being built and were moved here from the former architecture pages. They describe observations on earlier commits. Run ids, counts and class names are those of the time. The live results are backed by the files in [`docs/evaluation/samples/`](../evaluation/samples/README.md).

**In one paragraph.** Fixture replays of all six scenarios behaved as designed. One live bug-fix run completed after a human release approval. A live greenfield trial and a live brownfield run did not produce an acceptable result. A dated verification pass on 2026-10-01 covered tests, replays, operator controls and packaging.

## Fixture replays

Observed with `python3 scripts/checks/agent_smoke.py`. Approvals were made by the script and labelled `synthetic-fixture-test`; they show the mechanism, not human sign-off.

| Case | Observed result |
| --- | --- |
| Greenfield fixture | `COMPLETED`; 3 sandboxed candidate tests passed; audit valid |
| Brownfield fixture | `COMPLETED`; candidate tests passed; audit valid |
| Ambiguous fixture | Paused for clarification, invalidated downstream work, required a new patch hash, then `COMPLETED`; audit valid |
| Seeded bug-fix fixture | One regression test failed at redirect 30 (`302` expected, `429` actual); after the repair 5 tests passed; audit valid |
| Out-of-scope patch | `SAFE_STOPPED` before application |
| Invalid in-scope patch | `PAUSED` after one attempt, `FAILED` after two; diagnostics retained |
| Wrong approval hash | Rejected without changing run state |
| Rejected release | `NOT_APPROVED`, final |
| Simulated validation and patch interruption | Recovery returned to release review with the candidate diff applied once and a valid audit chain |

## Live runs

**Bug fix, completed.** Run `d44416f4-8d94-4d6a-9671-1c54ffd9f15f` of `scenarios/bugfix/scenario.json` used four model calls. Its generated regression test failed at visit 31 with `429` instead of `302`. Its generated repair removed the redirect limiter while keeping creation throttling. Five candidate tests passed and `verify-audit` returned `AUDIT_VALID`. The candidate diff was one deleted production line and one new 45-line regression test. The operator approved release hash `a4ba50caa030fb3dce329dfcadfdb0d05bad6f2efdc4ede55d96122c7ec6c979` and the run reached `COMPLETED`. The candidate stayed in its worktree; nothing was merged or deployed.

**Greenfield, not completed.** A live trial produced requirements, architecture, risk, plan and test artifacts, but from an empty baseline the model chose Node and TypeScript and returned a patch outside the intended Java project.

**Brownfield, failed.** Run `a5e9f971-13fd-46b6-8efc-4f62dcf1ff44` generated seven planning artifacts. Review of its first patch found malformed diff counts, inconsistent alias normalisation and missing tests; the proposal was sent back with [recorded feedback](brownfield-review.txt). The next patch call exceeded the 32,768-token output limit and used up the retry. The run ended `FAILED` after 12 model calls with an unchanged candidate and a valid audit chain.

In response, the feature workflow was changed to split production and test changes into two patch tasks and to add separate architecture, risk and test-plan documents. That change is a mitigation. It has not been shown to make large live runs succeed.

Other limits noted at the time: the product HTTP acceptance script runs separately and is not a release gate inside the orchestrator; the performance figures in the ambiguous scenario (100 requests per second, a p95 target) are proposed acceptance parameters, not measurements.

## Verification pass of 2026-10-01

Recorded when both applications were moved onto Spring Boot 4.1.1:

- Full Maven unit and integration run, followed by focused migration and HTTP-validation reruns: 77 tests in the final reports; no failures, errors or skips.
- Six CLI fixture replays: greenfield, brownfield, ambiguous and bug-fix completed; policy violation safe-stopped; retry exhaustion failed as designed. All audit checks passed.
- Operator HTTP checks passed with typed and validated request records, including invalid kind and mode, missing token, cross-origin request, stale approval hash, revision, clarification and rejection.
- Chrome desktop and mobile approval and revision flows passed (local evidence under `.runs/browser/`, not committed).
- Product HTTP acceptance passed; both readiness endpoints returned `UP` after restart.
- Executable Boot packages built; the packaged factory started on an alternate port and passed readiness and invalid-request checks.
- All 22 curated evidence checksums were intact. No live model calls were used.
