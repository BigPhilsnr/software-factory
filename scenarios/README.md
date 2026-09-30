# Reproducible engineering scenarios

Each folder is a self-contained replay: `scenario.json` defines its task graph and the adjacent text/patch files supply recorded agent outputs. Fixture mode still applies patches and executes real sandbox tests.

| Folder | What it demonstrates |
| --- | --- |
| `greenfield/` | Building a shortener from the empty project baseline. |
| `brownfield/` | Extending an existing product. |
| `ambiguous/` | Clarification before planning and implementation. |
| `bugfix/` | A failing regression on a deliberately buggy baseline, then a repair. |
| `policy-violation/` | Refusing an out-of-scope patch. |
| `retry-exhaustion/` | Bounded retries and terminal failure. |

These are historical, reproducible bundles. Their specs, patches and baselines retain their original bytes and package paths, including `com.example`, so they still apply to their pinned Git commits. Current application code uses `dev.softwarefactory` and `dev.shortener`. New feature runs use the reorganized `url-v4` baseline.

Persisted runs that reference the former `scenarios/NAME.json` path resolve to `scenarios/NAME/scenario.json` without changing their stored hashes. No historical Git tag is rewritten. Live feature-request specifications and candidate worktrees remain in ignored `.runs/`; immutable outputs remain in ignored `evidence/`.

Run all bundles from the repository root with `python3 scripts/checks/agent_smoke.py`. See the root README for Docker and Maven-cache prerequisites.
