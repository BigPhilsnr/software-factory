# ADR 0016: A separate control database, and Git worktrees as candidates

**Status:** accepted

**In one paragraph.** The factory keeps its state in its own PostgreSQL database, with credentials that only the factory process has. Each run works on a detached Git worktree pinned to a baseline commit. Neither the product database nor the checked-out branch is ever modified by a run.

## Context

The factory changes the shortener's code and decides whether those changes are acceptable. If approvals and audit rows lived in the product database, or if patches landed in the working checkout, a faulty or hostile change could interfere with the record of its own review.

## Decision

- **Control DB:** a second PostgreSQL instance (`control-db`, port 5434) with its own user. The sandbox and the agents receive no database credentials. `SourceContext` and the repository tools never read `.env`.
- **Candidates:** `GitWorkspace.create` runs `git worktree add --detach .runs/<run-id> <baseline commit>`. The baseline tag is resolved to a commit when the run starts. Reset is `git reset --hard` plus `git clean -ffdx`.
- **Evidence:** outputs are immutable files under `evidence/<run-id>/`; the database stores their hashes.
- **Completion** marks the run `COMPLETED`. Merging the candidate is a separate, manual step outside the factory.

## Consequences

- A run can be thrown away by deleting its worktree and evidence folder. The audit rows remain.
- Recovery is simple: reset to the baseline and re-apply the completed patches from evidence.
- Worktrees and evidence accumulate until pruned (`factory_cli.py prune`).
- The factory must run from inside the Git repository it works on, and scenario baselines must exist as tags in it.
- Two local databases are needed for development.

## Revisit when

- Candidates should become pull requests: push the worktree's diff to a branch after release approval.
