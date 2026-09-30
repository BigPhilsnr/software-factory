# Evaluation evidence and remaining gaps

The assignment asks for an explicit dependency graph, parallel paths with joins, persistent context, approvals, bounded recovery, guardrails, metrics, three engineering scenarios and a defensible summary. These are engineering requirements, not permission to publish, deploy or self-approve live work. No numeric scoring weights or guaranteed full-mark threshold were supplied.

## Evidence mapped to the rubric

| Criterion | Implemented evidence | How to verify | Remaining limit |
| --- | --- | --- | --- |
| Orchestration effectiveness | Stateful DAG; architecture/risk parallel branches; explicit joins; patch/release approvals; clarification; descendant invalidation; bounded retries with previous-error context; interrupted-patch rollback and revalidation; safe-stop | `TaskGraphTest`, default `RunEngineBehaviorTest`, opt-in `RunEngineResilienceTest`, six scenario replays, operator API smoke | Trusted workflow templates define topology; natural-language requests do not autonomously invent arbitrary DAGs. In-flight changes serialize behind a per-run lease. |
| System architecture | Separate product/control databases; isolated candidates; explicit workflow, agents, governance, execution, persistence and operator packages; shared CLI/UI application service | Module READMEs and architecture guide | Local single-host coordinator; persistence uses `RunStore`, while workspace/validator remain concrete collaborators. |
| Execution quality | Pinned baseline and scopes; bounded product source context; measurable criteria prompts; distinct architecture, risk and test-plan artifacts; separate production/test patches; real Git applicability and Maven execution | Versioned artifacts and candidate diffs; source-context regression; historical live bug-fix evidence | Historical fixture code is recorded, not live generation. Live greenfield and large brownfield trials failed; the new decomposition is not proof of arbitrary live feature completion. |
| Risk management | Exact-hash approval; artifact/audit integrity checks before advancing/release; scope enforcement; symlink/submodule refusal; secret-free validation container; timeout/resources; negative controls | Tamper/lease/recovery tests, patch policy tests, safe-stop/retry-exhaustion scenarios | Database owner can rewrite the chain; no external audit anchor or authenticated operator identity. Candidate Maven tests are not a hostile-build attestation. |
| Engineering principles | Real PostgreSQL/Flyway HTTP integration; concurrent alias uniqueness; validation; separate creation throttling; cache; bounded analytics; isolated regression execution | `PostgresHttpIntegrationTest`, product acceptance, unit tests | Single-instance cache/limiter; analytics may lag/drop. No measured distributed throughput or high-availability claim. |
| Decision defensibility | Explicit alternatives, residual risks, measured metrics, preserved failure evidence and human release gates | Decisions, risk register, run metrics, exact review hashes | Final live release still needs the operator's decision; synthetic test approvals are not human sign-off. |

## Reproduce the checks

Start both local applications and databases following the root README, warm Maven's dependency cache and pull `maven:3.9-eclipse-temurin-21`. Set `JAVA_HOME` to JDK 21, then run:

```sh
python3 scripts/checks/evaluate.py
```

This runs unit tests, three factory fault-injection tests, the real PostgreSQL/HTTP integration test, all six scenario replays, operator controls and the running-product acceptance check. It uses no paid model calls. It stores exit codes, durations, commit/dirty-tree information, test totals and full command logs in `.runs/evaluation/<UTC timestamp>/`. Any failure or skipped test makes the evaluation fail. Runtime output is intentionally ignored by Git. A curated [portable evidence bundle](samples/README.md) retains historical live successes/failures and local results, with original revision labels and checksum verification. See the [final summary](../SUMMARY.md) for assignment section 4.8.

The integration tests create only new UUID fixture runs and a disposable `contract_<uuid>` schema in the local shortener database on port 5433; the schema is removed afterward. The factory tests use the local control database on port 5434 and retain their run/audit records. Fault injection changes only newly created fixture evidence/state. A tampered evidence file is restored after the safe-stop assertion. Historical/user runs are not edited.

Run individual integration checks with `mvn -Pintegration -f factory/pom.xml -Dtest=RunEngineResilienceTest test` or `mvn -Pintegration -f shortener/pom.xml -Dtest=PostgresHttpIntegrationTest test`. Integration tests are JUnit-tagged and excluded from the default unit-test lifecycle; the profile includes them explicitly.

## Reliability measurements

Per-run metrics are available in the operator page, `GET /factory/api/runs/{id}` and `metrics <run-id>` in the CLI. `GET /factory/api/metrics` summarizes the latest 100 updated runs, separately for fixture and live modes.

- Completion rate = completed / terminal runs. Rejected, safe-stopped and failed runs remain in the denominator; unfinished runs do not. Deliberate negative/fault-injection tests therefore lower fixture completion rate. This is observed history, not an evaluation pass percentage.
- Retry offers count `RETRY_AVAILABLE`; retry executions count a subsequent task/validation start while a recorded failure is unresolved.
- Rollbacks count successful `PARTIAL_REPLAN` resets and interrupted recovery with `candidateReset=true`.
- Mean recovery time runs from the first recorded retryable failure to that task's next `TASK_DONE`, including operator wait. Unresolved failures stay visible and contribute no fictional zero-duration sample. No recovered samples produces `null`.
- End-to-end latency runs from creation to terminal time; an active run displays elapsed time. These are orchestration measurements, not URL redirect p95 or provider dollar/token budgets.

## Suggested reviewer walkthrough

1. Read the two module guides; explain the separation of model advice, deterministic authority and isolated execution.
2. Show an ambiguous fixture: answer its question, inspect the dependency graph, revise a proposal, and observe descendant invalidation and a new review.
3. Show the seeded bug-fix evidence: actual failing assertion, repaired candidate, passing suite and exact final diff.
4. Inspect real provider-generated evidence from the historical completed live bug-fix run. Show the failed live brownfield run too, and explain why it was stopped instead of presenting it as a success.
5. Run the evaluation suite; show tamper safe-stop, lease exclusion, recovery/revalidation, real DB uniqueness and metrics.
6. State the remaining boundaries: local operator identity, single-host reliability, no distributed load proof, no automatic merge/deploy, and no universal live-generation guarantee.

## Before claiming the prototype is fully submission-ready

The strongest remaining evidence would be bounded live greenfield, brownfield and ambiguous runs with independently reviewed executable output. Candidate HTTP acceptance is still a separate host-controlled check, not a mandatory per-candidate release gate; the new real-DB test validates the current checkout. Do not describe either as already solved. A short narrated demo and concrete human review of live release proposals are still submission work. Full marks depend on evaluator judgment and cannot be established by a self-assigned score.

## Clean-checkout verification

`python3 scripts/checks/clean_checkout.py` clones committed HEAD into a temporary directory and runs `mvn clean test`, excluding ignored sources and existing build outputs. It requires JDK 21 and writes the tested revision/results to `.runs/clean-checkout/`. The GitHub Actions workflow also runs default tests from a clean checkout and verifies curated evidence checksums. No hosted CI run is claimed before pushing to a configured remote.
