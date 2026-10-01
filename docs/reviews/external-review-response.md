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

## Correctness and security review — 2026-10-01

The follow-up findings were checked against `9ca44f8`. Findings below distinguish defects from recommendations; this is not a Sonar certification. No live provider calls are used for verification.

| Priority finding | Assessment and change |
| --- | --- |
| 1. Analytics blocks for 100 ms | Confirmed. Redirects now enqueue without waiting or cancelling cold writes. Queue capacity and database resources remain bounded; writer failures and queue rejection increment the failure counter. One warm connection and a five-second statement timeout reduce cold-write latency. Tests await eventual analytics instead of assuming immediate consistency. |
| 2. Databases exposed on all interfaces | Confirmed. Both Compose mappings bind to `127.0.0.1`; passwords can come from `.env`. Demo defaults remain for reproducible local setup. Existing volumes require explicit password rotation—changing `.env` does not alter stored roles. |
| 3. Dashboard date displays 1970 | Confirmed. Factory JSON writes ISO-8601 instants and still reads legacy numeric timestamps. |
| 4. Numeric-host bypass | Not reproduced on this revision. Octal notation was already explicitly rejected; Java URI parsing returns no host for the reported mixed hexadecimal form, so existing absolute-URL validation rejects it. Added regression cases for both and single-number hexadecimal notation. A blanket alphabetic-TLD rule was rejected because it would reject valid public IP literals and some internationalized names. This shortener never fetches targets; the risk is redirect abuse, not server-side SSRF. DNS names that resolve privately remain outside this literal policy. |
| 5. Decision saved before capacity check | Confirmed. Approval, clarification and revision check capacity under the same service lock as admission before touching persistence; shutdown follows that lock too. |
| 6. Missing evidence breaks run page | Confirmed. Display-only reads return an explicit unavailable placeholder. Missing artifacts return 404. The operator can inspect/reject a damaged run; approval verifies both recorded artifacts and the exact pending proposal first. |
| 7. UUID locks and state concurrency | Mixed. Two integer keys still contain only 64 bits and do not eliminate collisions. A collision blocks an unrelated run rather than permitting concurrent ownership, so the lock-key change was rejected. Added revision checks under the row lock, synchronized snapshot persistence, concurrent state maps and state-bound hashes for new audit events. Old audit records remain verifiable in their original format. |
| 8. Process lifecycle and output | Confirmed. Commands receive EOF on stdin, diagnostics are capped at 2 MiB and decoded as UTF-8, timeouts differ from nonzero exits, and cleanup terminates visible descendants and the parent. Docker cleanup also removes the invocation's named container. |
| 9. Git option/path ambiguity | Partly confirmed. Leading-option refs are now rejected, Git path inspection uses NUL-delimited numstat, and metadata paths, ambiguous/quoted headers and rename/copy headers are denied. Symlink/submodule modes were already denied; index-mode cases are covered too. |
| 10. Sandbox verdict authority | Confirmed limitations. Only a completed exit-1 test command with a single assertion failure can satisfy the red gate; timeout/Docker errors cannot. Mount delimiters are checked. The image is digest-pinned, the candidate source is read-only, and a factory-owned Maven definition controls build plugins and test configuration. Only the target directory is writable. This harness uses its pinned Spring Boot stack; dependency changes require a reviewed harness update. Candidate-written tests and XML are still evidence, not proof against adversarial test code. Independent HTTP acceptance and human review remain required. |
| 11. Prompt injection / URL exfiltration | Confirmed risk; mitigated at the capability boundary. Repository/history/tool content has per-block random delimiters. Page fetches require an exact URL registered from provider search evidence, strip queries/fragments, and authorize redirects again. Model-created URL paths or unrelated origins are rejected. Prompt delimiters are not a security boundary. Search queries still leave the machine for the configured provider; this is not a general DLP system. |

Additional findings:

- **Redirect load/cache:** malformed codes stop before repository access; missing codes have a two-second bounded cache; positive entries use access-order eviction rather than clearing the whole cache. Creation overrides a cached miss.
- **Rate limiting:** IPv6 peers share a `/64` bucket. Expired windows are removed; saturation still fails closed until the window rolls over. Evicting active clients would allow address churn to reset their limits. Forwarded headers remain deliberately untrusted for the documented direct, loopback deployment.
- **Request/error handling:** both applications reject bodies above 64 KiB before JSON parsing, including chunked bodies. Link-input errors use a dedicated exception; unexpected errors remain 500 and dependency errors remain 503, with sanitized server logging. Factory input, missing-resource and state-conflict responses use 400/404/409.
- **Evidence/audit:** identical evidence retries are idempotent; different bytes cannot overwrite the immutable artifact. Artifact verification hashes bytes. New audit events bind the exact state snapshot, and audit inspection uses a consistent database snapshot. A database administrator able to rewrite the entire chain is still inside the trust boundary; these hashes are not external notarization. Metrics' legacy detail parsing remains compatible with historical events.
- **Resources:** startup cleans its newly created workspace after a failed save only when persistence confirms the run is absent; uncertain commit outcomes preserve the candidate for recovery. Connection and statement waits are bounded. A connection pool and versioned control-schema migrations remain worthwhile production follow-ups, not prerequisites for a correct single-operator prototype. Persisted worktrees/evidence are intentionally retained for review; no automatic deletion of a user's runs was added.
- **Dashboard cache:** access-order eviction and per-entry in-flight futures replace global locking across database loads. Dashboard caching never authorizes engine transitions.
- **Chat:** a shared daily provider-request budget (default 80) and request/tool audit records now persist in the control DB; each agent invocation has a three-minute deadline. The limit counts attempted requests, not dollars/tokens. The ADK JSON routes already enforce same-origin requests and reject simple form posts; absence of the operator page's custom token alone was not a demonstrated CSRF bypass. Filter tests cover those controls.
- **UI:** unchanged DOM fragments are retained, run-list focus and active text selection are preserved, selection during a fetch queues a fresh load, run IDs are validated/encoded, failed approval restores its button, stale artifact responses are ignored, and successful refreshes clear old error notices. Small-text contrast is darker, the run group has an explicit role, and CSS rules are separated for readability; this is not a full accessibility certification.
- **Small cleanup:** removed unused count methods and the unreachable favicon alias entry; colocated `RedirectStats` with link queries; removed the governance-to-workflow import; typed the operator token. CLI usage errors exit nonzero and CLI rejection requires the reviewed hash.
- **Architecture/build/style proposals:** wholesale executor/service extraction, moving every constant into configuration, Flyway adoption, coverage/enforcer tooling are larger maintainability choices. They are not automatically correctness defects and were not bundled into this security patch. Integration tests remain explicitly opt-in and are run by `evaluate.py`; the integration database can now be configured through environment variables. Existing evidence and audit tests were overlooked by the review; new focused tests cover the confirmed gaps.

Verification commands: `mvn -Pintegration test`, `python3 scripts/checks/evaluate.py`, and the optional real-Chrome `scripts/checks/browser_smoke.cjs`. Generated results under `.runs/evaluation/` and `.runs/browser/` record the executed checks; a final pass must come from those results, not this checklist.
