# External review: verified findings and response

The supplied review was checked against `47fa6f6`, the assignment, source code, local run records and clean Git clones. Its numeric grades are estimates, not published rubric weights.

| Finding | Assessment | Response / remaining work |
| --- | --- | --- |
| Missing `EvidenceStore.java` in Git | Confirmed blocker. A fresh clone failed with the reported missing package. | Fixed in `8cb4d73`: root-anchored runtime ignores and tracked source. A new clone passed all 40 then-current default tests. Added a repeatable clean-clone check and CI. |
| Scenarios replay recorded code; ambiguous answer does not change fixture output | Confirmed. Artifact names do not prove independent executable-test generation. | State this in the final summary and scorecard. Preserve fixtures as deterministic control-plane tests; live scenario evidence remains separate work. |
| Live greenfield/brownfield failures | Confirmed. Tool smoke completion is not full software delivery. | Commit failed outcomes alongside the successful historical live bug-fix artifacts. |
| Re-planning is invalidation; templates and capped parallelism | Correct description of current limits. | Describe descendant invalidation/regeneration accurately. Arbitrary dynamic DAG planning and alternate-provider fallback remain unimplemented. |
| Gates are not separate objects | Correct structural observation. The assignment requires explicit gates, not a particular Java class structure. | Document enforced entry/exit conditions in the final summary. Semantic quality of model artifacts still needs review. |
| Default tests barely exercise `RunEngine` | Confirmed. DB/Docker tests were opt-in. | Add a `RunStore` boundary and six default behavior tests with copied in-memory state and real Git candidates. Retain integration checks for actual adapters. |
| No CI / no Testcontainers | CI absence confirmed. Testcontainers is an implementation option. | Add pinned GitHub Actions configuration for clean builds/tests and evidence checksums. Local integration setup remains; no hosted CI pass is claimed. |
| Analytics wait/count-only API, whole-cache eviction, peer-IP limiting, no expiry/deletion | Confirmed. Proxy behavior is a deployment limitation rather than a defect in direct local operation. | Record exact limitations and production follow-ups. Preserve focus on orchestration evidence rather than expanding optional product scope. |
| No final engineering summary | Confirmed. | Add [Final Engineering Summary](../SUMMARY.md), mapped to section 4.8. |
| Evidence not portable in Git | Confirmed. | Add a curated, redacted, checksummed [sample bundle](../evaluation/samples/README.md), including failures. Runtime histories remain ignored. |
| `.env` ignored | Correct. | No key is copied into source, CI configuration or evidence. |

## Reproduce

```sh
# JDK 21; tests committed HEAD without ignored/uncommitted files.
python3 scripts/checks/clean_checkout.py
python3 scripts/checks/verify_evidence.py

# Workflow behavior coverage; no DB/Docker/provider needed.
mvn -q -pl factory -Dtest=RunEngineBehaviorTest test

# With documented databases, applications and sandbox dependencies running:
python3 scripts/checks/evaluate.py
```

Clean-clone results go to `.runs/clean-checkout/<tested revision>/`; the full evaluation records its commit and dirty-tree state. Historical logs retain their original revision rather than being presented as proof of current HEAD. Clean-clone checks use the host's Maven cache; they are not an air-gapped dependency-installation test.

## Verification outcome

Code revision `08e8deb` passed **46 default tests in a fresh clone**, **50 tests with the integration profile**, all six scenario replays (including expected safe-stop/retry-exhaustion outcomes), operator controls and running-product HTTP acceptance. No failures, errors or skips occurred in Maven. The [committed verification bundle](../evaluation/samples/review-verification/results.json) and [clean-clone result](../evaluation/samples/review-verification/clean-checkout.json) retain exact revision and command results. The following commit adds evidence/documentation only; it does not alter the tested code.
