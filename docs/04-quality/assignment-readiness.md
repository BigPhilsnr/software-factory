# Assignment readiness and reviewer guide

This page maps the prototype to Sections 4–6 of **Assignment Agentic-Proficient Software Engineer.pdf**. The assignment asks for a runnable, governed SDLC prototype and three demonstrable scenarios; it does not publish scoring weights. A 95–100% score is a target, not a verified property or a guarantee. Evaluate behavior and evidence using the steps below.

## Start with reproducible evidence

Follow the [setup and testing instructions at the top of the root README](../../README.md). Then run:

```sh
python3 scripts/checks/evaluate.py
# With Node, puppeteer-core and Chrome configured:
python3 scripts/checks/evaluate.py --browser
```

Read `.runs/evaluation/<timestamp>/results.json` and its command logs. The report distinguishes the commit from the working source, records source hashes and rejects edits during evaluation. `passed: true` requires real tests in **both** modules, zero failures/errors/skips, and every requested command succeeding. The Maven step runs `clean verify` with integration tests, so stale test reports cannot create a pass. Source hashes establish what was tested; they are not an independent attestation. HTTP smoke checks use already-running processes; restart them after code changes.

[The scorecard](scorecard.md) preserves earlier measurements. [Historical live evidence](../evaluation/samples/README.md) remains labelled with its original revision and includes unsuccessful attempts. Do not represent those results as current-code verification.

The [2026-10-01 validation snapshot](validation-2026-10-01.json) records a successful final-source local evaluation: 439 Java tests, four script tests, six expected scenario outcomes, HTTP checks and separate browser checks. See the [scorecard](scorecard.md) for provenance and coverage. No paid generation was used.

## Requirements and evaluation criteria

| Assignment requirement / criterion | Implemented behavior and primary evidence | Reviewer demonstration / remaining boundary |
| --- | --- | --- |
| Requirement understanding; well-defined and ambiguous input | `FeatureScenario` creates requirements with measurable criteria, explicit questions and assumptions. A `CLARIFY` gate precedes design; operator answers are immutable evidence passed to architecture/risk agents. `FeatureRequestGovernanceTest` verifies the pause and context propagation. | Run the ambiguous scenario, inspect `understand`, answer the questions and show the answer in the run lineage. Free-text feature requests now have this checkpoint too. |
| Decomposition; orchestration effectiveness | `TaskGraph` validates dependencies; `AdvanceRun` selects ready tasks. Parallel artifact branches join before dependent work. Production patch, independent tests, validation and release have distinct responsibilities. | Inspect a scenario graph and the `PARALLEL_JOIN` audit event. `TaskGraphTest`, engine tests and scenario replays validate sequencing. Workflow topology is trusted, not arbitrarily authored by a model. |
| Non-linear execution; dynamic re-planning | `ReviseRun` invalidates the changed task and its descendants, rebuilds the candidate and requires fresh validation and approvals. Unaffected evidence remains. Recovery replays completed patches after interruption. | In the ambiguous run, request changes on the patch and show the new proposal hash and downstream versions. `RunEngineApprovalTest`, `RunEngineBehaviorTest` and `RunEngineResilienceTest` exercise revision, restart and stale approvals. This is dependency-based replanning, not unrestricted graph synthesis. |
| Brownfield reasoning; architecture/system design | Agents receive the actual candidate source, schema and API contract plus upstream artifacts. Separate product/control databases and candidate worktrees enforce isolation. ArchUnit enforces package boundaries. New features pin committed `HEAD` at creation. | Show `baselineCommit`, impacted files and the reviewed diff. Later commits and uncommitted edits cannot alter that run. Historical fixtures intentionally keep their original tags. |
| Realism and quality of engineering outputs | Two runnable Spring Boot applications; public API contract and Flyway migrations; unit, REST Assured and PostgreSQL integration tests. Candidate code is actually compiled and tested offline. Changed test classes must execute. | Inspect the candidate diff and sandbox test report. Bug-fix scenario proves red-before-fix and green-after-fix. Fixture text is recorded; fresh model performance still needs live evidence. |
| Risk management; policy and change control | Scoped patches, applicability checks, exact-hash human approvals, budgets, deadlines, bounded retries, HMAC audit/evidence checks, no credential-bearing shell tools and an offline container without host network/credentials. | Show stale approval refusal, policy-violation safe-stop and retry-exhaustion failure. `PatchPolicyTest`, `PatchScopeTest`, audit tests and real HTTP integration tests cover bypass attempts. Local identity is an operator assertion, not enterprise authentication. |
| Fallback, rollback and reliability | Infrastructure outages pause without consuming candidate retries. Failed tasks expose diagnostics; revisions reset/rebuild candidates. Audit-derived success, retry, rollback, recovery-time and latency metrics survive process restarts. | Inspect `/factory/api/metrics` and per-run metrics. There is no silent substitution of fixture output for failed live generation. Rollback covers candidate code, not production data migrations. |
| Secure, modular, testable, reliable, scalable code | Feature packages, constructor injection, architecture tests, SQL constraints, timeouts, bounded caches/queues, isolated analytics pool and graceful shutdown. Product launch strips model, factory and control-DB credentials. Formatting, static/security analysis and coverage floors gate Maven/CI. | Run the quality gate and `ProductLauncherTest`. Scale-out requires distributed rate limiting/cache policy; analytics are best effort and no capacity claim is made without a benchmark. See [security](security.md) and [risks](risks.md). |
| Decision defensibility; human oversight | ADRs document alternatives, consequences and revisit triggers. Humans confirm scope and approve production/test patches and final release. Completion leaves a reviewable worktree; it does not merge or deploy. | Open the evidence beside the diff before approving. Explain why pinned workflow topology and deterministic policy enforcement constrain agents. [ADR index](../02-architecture/decisions/README.md). |
| Final engineering summary | The feature documentation stage receives requirements, operator clarification, architecture, risk, plan, test plan and validation evidence. It must cover rationale, changed contracts/files, acceptance-to-test mapping, assumptions, residual risks, setup and rollback. | Inspect `documentation-vN.txt` before release. A model summary is review material; validation status and release approval remain deterministic engine decisions. |

## Demonstrate the required scenarios

Allow roughly 15–25 minutes after setup, depending on Docker/build speed. Fixture actions are free of model charges. Use the operator page for actual human review; automated scenario replay uses explicitly synthetic approvals.

1. **Greenfield:** select Greenfield, Fixture demo, create and start. Show the empty baseline, requirements, parallel design/risk work, dependency plan and resulting core APIs/schema/tests. Review the patch, approve its current hash, inspect actual validation, then approve release.
2. **Brownfield:** repeat with Brownfield. Show the existing baseline and compatibility constraints, the aliases/rate-limiting change, independent tests and the final diff. Explain how scope restrictions prevent factory edits.
3. **Ambiguous:** start Ambiguous requirement. Before answering, show that downstream work has not executed. Answer with a single instance, immutable links, a 60-second cache and an explicit latency/load target treated as a target, not a measurement. At patch review, request changes once; show stale approvals becoming unusable and dependent evidence regenerated. Complete validation and review.
4. **Negative controls:** run `agent_smoke.py`. Policy violation must end `SAFE_STOPPED`; retry exhaustion must end `FAILED`; both audits must still verify. Those outcomes are successful safety demonstrations, not successful feature delivery.
5. **Ownership:** choose a completed run, inspect its test report and final diff, run `python3 scripts/factory_cli.py verify-audit RUN_ID`, and explain one residual risk and the relevant ADR. Show where the candidate is kept and that no deployment occurred.

## What remains before claiming near-full marks

These are submission acceptance conditions, not hidden completed work:

- **Fresh live output evidence:** demonstrate bounded greenfield and brownfield live delivery and a meaningful clarification/revision on ambiguous live input. Record the actual provider/model, pinned baseline, prompts/artifact hashes, tests, human decisions, final diff and limitations. Current historical evidence proves one live bug fix, not reliable delivery of all three required scenarios. A live run that stops remains useful risk-control evidence but does not count as delivered functionality.
- **Human review:** inspect the generated API/schema changes and tests for correctness, rather than treating a green test run or generated summary as proof. Keep decisions tied to exact hashes; synthetic smoke-test approvals are not submission sign-off.
- **Submission reproducibility:** commit the intended reviewed code, include scenario tags in the submitted repository, run the same evaluation from that clean revision, and attach its report. Uncommitted-source evaluation is useful locally but is not proof that a reviewer received those exact files.
- **Performance claims:** if claiming throughput/latency or multiple-instance capacity, add a repeatable workload, measured percentiles, environment and failure behavior. Otherwise retain the documented single-instance scope.

The 2–3-day assignment scope supports bounded prototypes and explicit trade-offs. Adding Kafka, Redis, distributed scheduling or more abstractions solely to enlarge the stack would not close the live-output and evidence gaps above.
