# ADR 0012: Packages named after the story, with dependencies enforced by ArchUnit

**Status:** accepted

**In one paragraph.** Each module's top-level packages are the chapters of what the module does, not technical layers. A test in each module states which package may depend on which and fails the build on a violation or a cycle.

## Context

Both modules started with layer-style or mixed packages (`workflow`, `persistence`, `execution`, `links`, `bootstrap`). A reader could not tell from the tree what the system does, and nothing stopped a low-level class from importing a high-level one.

## Decision

- **Factory** (`dev.softwarefactory`): `scenario`, `run`, `generation`, `candidate`, `validation`, `governance`, `audit`, `operator`, `platform`. `StoryArchitectureTest` defines them as layers: `operator` is used by nobody; `run` only by `operator`; `generation`, `validation` and `audit` only by `operator` and `run`; `candidate`, `governance` and `scenario` by a stated set of packages above them. It also forbids cycles between top-level packages, between `operator` sub-packages, and inside `generation`.
- **Shortener** (`dev.shortener`): `shorten`, `redirect`, `analytics`, `link`, `platform`. `PackageDependencyTest` states that `platform` and `link` know nothing of the use cases, `shorten` never touches `redirect` or `analytics`, `redirect` never touches `analytics`, and there are no cycles.
- Every package has a `package-info.java` that says which chapter it is and what it may depend on.
- Types are package-private unless another package needs them.

## Consequences

- The package list is a table of contents; the module READMEs follow it.
- Architecture drift fails `mvn test` like any other regression.
- A few placements are driven by the rules rather than by taste. For example `RunMetrics` lives in `audit` because it reads audit events, and the cached repository lives in `redirect` because that is the use case it serves.
- Recorded scenario baselines and evidence still use the old package names. They are replayed against their pinned commits and are not rewritten.

## Revisit when

- A package grows a second, unrelated responsibility: split it and extend the rules.
