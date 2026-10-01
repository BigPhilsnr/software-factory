# ADR 0008: Compute the release diff through a private Git index with forced add

**Status:** accepted

**In one paragraph.** The diff that validation pins and the operator approves is produced by adding every file in the candidate to a temporary Git index with `--force`, then diffing that index against the baseline commit. Ignored and untracked files are included, so a candidate cannot hide a change from review.

## Context

`git diff` shows only tracked files. `git status` and `git add` skip files matched by `.gitignore`. A patch may legitimately add a `.gitignore`, and a patch could use one to make another new file invisible to an ordinary diff while the build still compiles and runs it. Text conversion filters and external diff drivers configured in a repository can also change what a diff displays.

## Decision

`GitWorkspace.diff(candidate, baselineCommit)`:

1. creates a temporary directory and points `GIT_INDEX_FILE` at it, so the candidate's own index is never touched;
2. runs `git add --all --force -- . ':(top,exclude)shortener/target'`, which stages everything except the validator's build output;
3. runs `git diff --cached --binary --no-ext-diff --no-textconv --no-renames <baselineCommit> --`;
4. deletes the temporary index.

All Git commands also run with hooks, the filesystem monitor and path quoting fixed (`core.hooksPath=/dev/null`, `core.fsmonitor=false`, `core.quotePath=true`).

`ValidateTask` stores the SHA-256 of this diff as `validatedCandidateHash`. `ReleaseTask` recomputes it, refuses to continue if it differs, and asks the operator to approve a hash over the same diff.

## Consequences

- What is reviewed is what was tested, byte for byte, including binary files.
- A `.gitignore` in a patch cannot hide anything. `PatchPolicy` additionally sends any `.git*` file to operator approval.
- Renames show up as a delete plus an add, which is longer to read but unambiguous.
- The diff is bounded: 60 s and 64 MiB of output, after which the command fails.

## Revisit when

- The build output location changes (`GitWorkspace.BUILD_OUTPUT`) or validation covers more than the shortener module.
