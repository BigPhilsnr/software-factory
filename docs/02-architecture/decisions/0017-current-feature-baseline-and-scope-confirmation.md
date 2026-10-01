# 0017: Pin new features to the current commit and confirm scope before design

## Status

Accepted.

## In one paragraph

Free-text feature requests start from committed `HEAD`, resolved once to an immutable SHA by `StartRun`. Requirements analysis is followed by an explicit operator clarification checkpoint before the architecture/risk branches. This prevents work on a stale product and prevents an agent's unresolved assumptions from becoming implementation authority.

## Context

The previous `url-v4` feature baseline used an older package layout than the working application and trusted validation build. New features could therefore be designed for yesterday's product. The requirements/risk agents could mention ambiguity, but the first mandatory human checkpoint came at patch review, after the assumptions had already influenced design and code.

## Decision

- `FeatureScenario` names `HEAD`; `StartRun` resolves and persists the commit before creating the detached candidate. Later commits cannot move an existing run's baseline.
- Uncommitted files are excluded. The operator commits intended product changes before creating a request. The factory never commits the operator's work on their behalf.
- After the requirements artifact, `CLARIFY` requires answers or explicit scope confirmation. Architecture and risk depend on both the requirements and that answer, preserving the decision lineage.
- The final documentation task receives requirements, clarification, architecture, risk, plan, independent test plan and validation output directly. It can cite actual evidence rather than reconstructing design rationale from code alone.
- Historical fixture scenarios retain their tagged baselines. Existing feature runs retain their saved scenario and baseline; no in-place migration is performed.

## Consequences

New requests match the committed product and retain reproducibility without requiring a new tag for every feature. Every request adds one human interaction, even when well-defined; the operator can confirm the stated scope rather than answer invented questions. Fixed workflow topology remains a deliberate governance constraint. This change improves context and oversight, but does not prove model-generated features will compile or meet the requirement.

`FeatureRequestGovernanceTest` uses real Git and the real engine with a scripted model to verify baseline isolation, the blocked design branches, propagation of the answer and immutable baseline behavior after another commit. Existing revision tests cover downstream invalidation and fresh approvals.

## Revisit when

Operators need explicit baseline selection across maintained release branches, or measured workflow latency justifies a policy-defined way to skip scope confirmation for narrowly pre-approved tasks. Any such policy must remain outside model authority.
