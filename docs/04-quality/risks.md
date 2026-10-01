# Risk register

**In one paragraph.** These are the delivery and operational risks of the system: ways it can disappoint that are not attacks. Security threats are in the [security model](security.md) and are not repeated here. Likelihood and impact are engineering judgments for a single-host deployment, not measurements.

## Register

| # | Risk | Likelihood / impact | Control in place | Remaining exposure and decision |
| --- | --- | --- | --- | --- |
| 1 | The model produces wrong code or a malformed patch | High / high | Small scoped patch tasks; applicability check before review; sandbox tests; one retry with the previous failure in the prompt; operator review of every patch in the feature workflow | Model quality is not guaranteed. The run pauses or fails; it never substitutes a fixture silently. |
| 2 | Live runs do not complete for large changes | High / medium | Production and test changes are separate patches; a response that hits the output limit is rejected rather than truncated | Only one live bug-fix run is recorded as completed; live greenfield and brownfield attempts failed ([recorded evidence](../evaluation/samples/README.md)). Treat live feature delivery as unproven. |
| 3 | Passing fixture replays are mistaken for proof of model ability | Medium / high | Fixture and live runs are labelled everywhere; metrics are reported separately per mode; scripts label their approvals `synthetic-*` | A reader may still over-read a green run. The documentation states the limit on its first page. |
| 4 | A crash in the middle of a patch leaves the candidate half-changed | Medium / high | Recovery resets the candidate, re-applies completed patches from evidence and re-queues downstream work, including validation (`RunEngineResilienceTest`) | Depends on the host's disk and database surviving. No failover to another host. |
| 5 | Validation cannot run (Docker down, image or Maven cache missing) | Medium / low | Preflight shown on the page and checked before advancing; classified as a platform failure, so no retry is consumed | The run waits until the operator fixes the environment. |
| 6 | The validation harness falls behind the shortener's dependencies | Medium / medium | `ShortenerValidationPomDriftTest` fails the build when the trusted POM and `shortener/pom.xml` differ | A candidate that needs a new dependency cannot be validated until the trusted POM is updated by hand. |
| 7 | Visit counts are lost or late | Medium / medium | Coalesced batches, retry of failed flushes, drop counters, final flush on shutdown | Counts are best effort by design ([ADR 0003](../02-architecture/decisions/0003-coalesced-analytics-writes.md)). Not suitable for billing. |
| 8 | Cache and rate limiter misbehave with more than one shortener instance | High if scaled out / medium | Documented single-instance assumption | Shared stores are required before scaling out ([ADR 0002](../02-architecture/decisions/0002-hand-rolled-rate-limiter-and-caffeine-cache.md)). |
| 9 | Unknown performance | Medium / medium | Fail-fast timeouts, bounded pools and queues | No load test has been recorded. Capacity is unknown. |
| 10 | Disk fills with worktrees and evidence | Medium / low | `factory_cli.py prune` | Manual. Nothing is deleted automatically. |
| 11 | The audit key is lost or changed | Low / high | A stable key is required for live runs; the development key is created once and never replaced | Existing chains can no longer be verified. There is no rotation procedure ([ADR 0007](../02-architecture/decisions/0007-hmac-audit-chain-with-versioned-schemes.md)). |
| 12 | An ADK or Anthropic SDK upgrade breaks the custom model adapter | Medium / medium | Versions pinned; protocol tests against a fake provider | The adapter must be re-tested on every upgrade ([ADR 0011](../02-architecture/decisions/0011-custom-thinking-aware-claude-adapter.md)). |
| 13 | The quality gate cannot run on a newer JDK | Certain on JDK 26+ / low | Documented JDK range 21 to 25; CI uses 21 | Lifts when the PMD plugin supports newer class files. |
| 14 | The two module POMs drift apart in plugin versions or rules | Low / low | Shared rule files in `build-config/` | Plugin configuration is duplicated because the modules do not share a parent. |
| 15 | A migration cannot be undone | Low / high | Additive, forward-only migrations; integration tests run them on a fresh schema | Rolling back code does not roll back schema or data. |
| 16 | Chat answers are poorly grounded in the documentation | Medium / low | Chat has repository tools and can read `docs/` on request | The chat's fixed context list still names four pre-reorganisation paths, which now hold only pointers. See the open item below. |

## Ownership

- The **operator** owns scope, assumptions and every release decision.
- **Deterministic code** owns enforcement: scopes, policy, budgets, integrity checks.
- **Agents** own proposals only. A risk document written by an agent can raise a concern; it cannot lower a gate.

## Open items

| Item | Why it is open |
| --- | --- |
| Update `CONTEXT_FILES` in `operator/chat/ChatConversation.java` to the numbered documentation paths | The list is in Java source. Until it changes, `docs/architecture/` and `docs/operations/` keep four pointer files so the paths resolve. |
| Decide whether the shortener's metrics should be reachable | `application.yml` lists `metrics` under exposed endpoints, and `PublicApiSecurity` denies it. The meters exist but cannot be read over HTTP. |
| Allow `SHORTENER_FORWARD_HEADERS_STRATEGY` in `.env` | `application.yml` reads it, but the launchers' `.env` allowlist rejects it, so it can only be exported in the shell. |
| Move the feature-request baseline forward | `FeatureScenario` starts every feature run from the tag `url-v4`, which is 15 commits behind `HEAD` and has the previous shortener package layout. The sandbox's trusted POM mirrors the current `shortener/pom.xml`, not the one at that tag. |
| Demonstrate a live feature run end to end | Needed before claiming live delivery beyond the recorded bug fix. |
