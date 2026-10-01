# History

**In one paragraph.** Everything in this folder describes the past: the plan the project started from, the summary written for its first submission, the answers given to reviewers, and notes on runs that were made against earlier commits. None of it describes the current code. Class names, package paths, test counts and "current" statements in these files belong to the commits they name.

## Contents

| Document | What it is | Refers to |
| --- | --- | --- |
| [Implementation plan](implementation-plan.md) | The original assignment interpretation and target design, including goals that were never built | Before the first implementation |
| [Engineering summary](engineering-summary.md) | The summary written for the original submission: plan, artifacts, gates, validation, assumptions, remaining work | Commit `08e8deb` and earlier |
| [External review response](external-review-response.md) | Two external reviews, finding by finding, with what was confirmed, changed or declined | Commits `47fa6f6`, `08e8deb`, `9ca44f8` |
| [Brownfield review feedback](brownfield-review.txt) | The operator feedback recorded for a failed live brownfield proposal | Run `a5e9f971` |
| [Run and verification notes](run-notes.md) | What was observed in early fixture replays and live runs, and one dated verification pass | Earlier commits, dated in the text |

## Recorded evidence

[`docs/evaluation/samples/`](../evaluation/samples/README.md) is the machine-checkable part of this history: exported artifacts, patches, test reports and results of earlier runs, with a checksum manifest.

- It stays at that path because `scripts/checks/verify_evidence.py` reads it there and CI runs that script.
- Its files are never edited. A change would fail the checksum check, which is the point.
- File paths inside the samples use the package names of their time (`com.example`, `links/`, `workflow/`). That is expected.

## How to read these files

- For what the system does now, start at the [documentation map](../README.md).
- For current test counts and coverage, see the [scorecard](../04-quality/scorecard.md).
- Where a historical file says a follow-up is "not yet done", check the [decision records](../02-architecture/decisions/README.md) before assuming it still is. Flyway migrations, connection pooling, coverage and enforcer rules, the operator token on chat commands and the keyed audit chain were all added after these files were written.
