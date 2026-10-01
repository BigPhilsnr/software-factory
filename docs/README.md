# Documentation map

**In one paragraph.** The folders are numbered in reading order: what the system is, how it is built, how to run it, how its quality is checked, and how it got here. Each document starts with a one-paragraph summary, so you can stop reading as soon as you have what you need. Package-level guides live next to the code in [`factory/README.md`](../factory/README.md) and [`shortener/README.md`](../shortener/README.md).

## Reading order

| Step | Document | What it answers |
| --- | --- | --- |
| 1 | [Overview](01-overview/README.md) | What is this, who uses it, what talks to what? |
| 1 | [Glossary](01-overview/glossary.md) | What do "run", "task", "candidate", "evidence", "approval gate", "lease" and "audit chain" mean? |
| 2 | [Factory architecture](02-architecture/factory.md) | Components, run lifecycle, sequences and trust boundaries of the control plane. |
| 2 | [Shortener architecture](02-architecture/shortener.md) | The three request flows of the product. |
| 2 | [Data model](02-architecture/data-model.md) | Both database schemas, the files on disk and the audit hash chain. |
| 2 | [Decision records](02-architecture/decisions/README.md) | Why each significant choice was made, one decision per file. |
| 3 | [Runbook](03-operations/runbook.md) | Start, stop, configure, observe, prune and troubleshoot. |
| 3 | [Operator guide](03-operations/operator-guide.md) | Drive a run from the web page, the chat or the CLI. |
| 3 | [Agent tools](03-operations/agent-tools.md) | What the model can read and fetch, and the limits on it. |
| 4 | [Testing strategy](04-quality/testing.md) | What each kind of test proves and how to run it. |
| 4 | [Quality gate](04-quality/quality-gate.md) | The static checks that `mvn verify` enforces. |
| 4 | [CI pipeline](04-quality/ci.md) | What runs on every push. |
| 4 | [Scorecard](04-quality/scorecard.md) | Measured test counts and coverage, and evidence per quality criterion. |
| 4 | [Security model](04-quality/security.md) | Assets, threats, controls and residual risk. |
| 4 | [Risk register](04-quality/risks.md) | Delivery and operational risks that are not security threats. |
| 5 | [History](05-history/README.md) | The original plan, the submission summary, review responses and recorded evidence. |

## Pick a path by role

- **New engineer, ten minutes:** root [README](../README.md) quickstart, then [overview](01-overview/README.md) and [glossary](01-overview/glossary.md).
- **Reviewer:** [scorecard](04-quality/scorecard.md), [security model](04-quality/security.md), [decision records](02-architecture/decisions/README.md), then [factory architecture](02-architecture/factory.md).
- **Operator:** [runbook](03-operations/runbook.md), then [operator guide](03-operations/operator-guide.md).

## Folders that are not part of the reading order

| Folder | Why it exists |
| --- | --- |
| [`evaluation/samples/`](evaluation/samples/README.md) | Recorded evidence from earlier commits, protected by a checksum manifest. `scripts/checks/verify_evidence.py` reads this exact path, so it does not move. Its contents are historical and are never edited. |

## Conventions

- Diagrams are Mermaid and render on GitHub. Each shows one idea and has a one-sentence caption.
- Commands are written to be run from the repository root.
- Statements about behaviour describe the code at the current commit. Anything else is under [history](05-history/README.md) and says which commit it refers to.
