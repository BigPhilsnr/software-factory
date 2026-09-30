# Browser operation

Start `control-db`, then run `python3 scripts/factory_web.py` from the repository. The launcher compiles the web entry point, loads `.env`, and starts the genuine Java ADK Dev UI and the factory operator page on loopback port 8000. On this Mac it falls back to the Homebrew JDK 21 if the configured Java is older. Elsewhere, set `JAVA_HOME` to JDK 21 or later.

- Operator page: http://localhost:8000/factory/
- ADK chat: http://localhost:8000/dev-ui/?app=software_factory

The operator page and chat share the existing PostgreSQL control plane. ADK chat answers ordinary questions through a read-only model grounded in allowlisted repository documentation and shortener sources. It remembers the last six exchanges per session (in memory, up to 128 sessions). Explicit slash commands route deterministically to the control plane; model answers cannot grant approval or create runs. Chat calls incur provider charges separately from the per-run model-call budget. The existing ADK/Claude runtime still generates engineering artifacts in background workers.

## Request and review work

Chat and live task agents can now search the web, read public HTTPS pages, inspect source files, search code, inspect Git and check the time. Send `/tools` or read the [tool guide](agent-tools.md) for examples, limits and costs. Chat shows a tool activity footer; workflow calls appear in the operator audit activity.

Enter a feature in the operator form or send `/feature YOUR_REQUIREMENT` in ADK chat. Ordinary chat messages discuss the project without creating runs. This saves a run without starting paid calls. Select **Start / resume**, or send `/advance` in chat, to begin generation. New requests target the pinned `url-v4` shortener baseline and use a fixed Java/Spring workflow: requirements, parallel architecture/risk analysis, a joined plan, an independent test plan, production patch, executable test patch, sandbox validation, documentation and release review. This UI accepts feature requirements; it does not grant permission to change control-plane policy or arbitrary repository paths.

The operator page shows dependency edges and measured run reliability (elapsed time, retries, rollbacks, replans and recovery time). The metrics endpoint `/factory/api/metrics` separates recent fixture and live outcomes. The operator page polls progress and audit activity every two seconds. Select artifacts to read plans and test reports. When the run needs clarification, its question and answer form appear. A proposed patch is shown with its exact approval hash; check the review acknowledgement and select **Approve & resume**, **Request changes & resume**, or **Reject run**. Approval applies only that hash. Changed/stale hashes are rejected. Revisions record feedback and invalidate the affected descendants while preserving existing call budgets and retry counts.

Final release approval marks the isolated run complete. It does not merge, push, deploy or modify the main checkout. Terminal failed/rejected runs remain inspectable and cannot be resumed; start a new run when appropriate.

## ADK chat commands

| Input | Result |
| --- | --- |
| A plain-language question or feature discussion | Answer using repository context and recent conversation; no workflow action |
| `/feature requirement` | Save a new live run and select it |
| `/help` | Show commands and operator-page link |
| `/tools` | Show available browsing and repository tools |
| `/demo bugfix` | Start a fixture demonstration |
| `/runs` | List recent persistent runs |
| `/select RUN_ID` | Select an existing run in this chat session |
| `/status` | Refresh its status |
| `/advance` | Start/resume background work |
| `/review` | Show exact pending hash and link to the full diff |
| `/approve EXACT_HASH` | Record approval and resume |
| `/changes TASK_ID feedback` | Record feedback, invalidate dependent work and resume |
| `/answer answer` | Record clarification and resume |
| `/reject` | Reject the currently pending proposal |

Bare `approved`, `yes`, or `continue` are not approval commands. Only explicit user command routing or the operator controls can grant approval. ADK's Events view shows chat interactions; factory task/model-call activity and persisted evidence are shown on the linked operator page. They are distinct event stores.

## Local operation and verification

The server listens on `127.0.0.1` and permits same-origin browser requests. Operator API mutations also require a page-issued token. This is a local, single-operator prototype, not an authenticated multi-user approval service. Keep it on loopback. API keys remain in the server environment and are not returned to the browser.

Factory runs, feedback, artifacts and audit history survive server restarts. ADK chat sessions, selected chat runs and worker activity indicators are in memory. After a restart, use the operator run list or `/select`; resume an interrupted run explicitly so existing recovery rules apply.

With the server running, `python3 scripts/checks/web_smoke.py` exercises fixture clarification, revision, exact-hash approval, stale-hash rejection, validation, rejection, audit verification and local request protections. Root Maven tests cover the trusted feature template and command authority. Browser testing additionally exercises native ADK chat, plain-language feature intake, approval buttons and mobile layout.

UI integration does not eliminate model-generation failures: the earlier large live greenfield/brownfield trials did not complete. New feature workflows separate production and executable test patches, but arbitrary new features have not been proven to complete end-to-end. Fixture demonstrations are explicitly labeled, and starting a live run makes paid provider calls.
