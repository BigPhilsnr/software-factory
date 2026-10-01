# Scenarios

**In one paragraph.** A scenario is a folder with a `scenario.json` (a requirement, a baseline Git tag and a task graph) and the recorded output of every task. In fixture mode the factory replays those recordings: it still creates a candidate worktree, applies the recorded patches, runs the tests in the sandbox and waits for approvals. Four scenarios run to completion and two exist to prove that the factory stops when it should.

## The six scenarios

| Folder | Baseline tag | Tasks | What it demonstrates | Expected end state |
| --- | --- | --- | --- | --- |
| `greenfield/` | `project-start` | 10 | Building the shortener from an empty project | `COMPLETED` |
| `brownfield/` | `url-v1` | 11 | Adding aliases and creation rate limiting to an existing product | `COMPLETED` |
| `ambiguous/` | `url-v2` | 11 | A vague requirement: the run stops for a clarification before planning | `COMPLETED` |
| `bugfix/` | `url-v2-buggy` | 7 | A regression test that must fail on the buggy baseline (`VALIDATE_RED`), then a fix that makes the suite pass | `COMPLETED` |
| `policy-violation/` | `url-v2` | 1 | A patch that reaches outside its write scope | `SAFE_STOPPED` |
| `retry-exhaustion/` | `url-v2` | 1 | A patch that cannot be applied: one retry, then failure | `FAILED` |

The operator page and the chat command `/demo` offer the first four. The last two are run by the checks.

## What is in a folder

| File | Purpose |
| --- | --- |
| `scenario.json` | `id`, `requirement`, `baselineTag` and `tasks`. Each task has an `id`, `stage`, `kind`, `role`, `dependsOn`, a `prompt`, the `fixture` file to replay, and for patches a `writeScope` and `requiresApproval`. |
| `*.txt` | Recorded output of an `ARTIFACT` task |
| `*.patch` | Recorded output of a `PATCH` task |

A fixture file must be inside its scenario folder; a path that escapes it safe-stops the run.

## Run them

```sh
# All six, with synthetic approvals and audit verification (needs control-db, Docker, a warm ~/.m2)
python3 scripts/checks/agent_smoke.py

# One, by hand
python3 scripts/factory_cli.py start scenarios/bugfix/scenario.json fixture
python3 scripts/factory_cli.py advance <run-id>
```

Prerequisites and the full CLI are in the [runbook](../docs/03-operations/runbook.md#start) and the [operator guide](../docs/03-operations/operator-guide.md#cli).

## These are recorded history

- The patches and baselines keep their original bytes and package names (`com.example`, later `dev.shortener.links` and so on), because they must apply to the commits their tags point at. The current code uses different packages. Do not "fix" them.
- A run pins the SHA-256 of its `scenario.json` when it starts. Editing the file afterwards pauses existing runs of it until they are revised.
- Runs created before the folders were introduced refer to `scenarios/NAME.json`. `ScenarioFiles` resolves that to `scenarios/NAME/scenario.json` without changing the stored hash.
- In the ambiguous scenario the operator's answer is recorded and changes the approval hashes, but the replayed patch is the same whatever the answer says. Fixtures prove the control flow, not the model.

Feature requests made through the operator page do not use these folders. They use the fixed workflow in `FeatureScenario`, start from committed `HEAD` (resolved to an immutable commit SHA at creation), and store their generated scenario under `.runs/requests/`.
