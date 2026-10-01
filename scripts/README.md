# Scripts

**In one paragraph.** Three launchers start the applications and the factory CLI with settings from `.env`. The scripts under `checks/` verify the system from the outside: over HTTP, through the CLI, in a browser, or from a fresh clone. Run everything from the repository root. Only one script makes paid model calls, and it requires an explicit `--live` flag.

## Launchers

| Script | Does | Notes |
| --- | --- | --- |
| `shortener.py` | Starts the shortener with `mvn spring-boot:run` | Removes the model settings from the environment first |
| `factory_web.py` | Compiles the factory and starts the web server on `127.0.0.1:8000` | Operator page, chat and API |
| `factory_cli.py <command> ...` | Runs one factory CLI command and prints JSON | Refuses a `live` start when `ANTHROPIC_API_KEY` is missing or a placeholder |

All three read `.env` through the same parser. It accepts a fixed list of variable names and stops with `Invalid .env entry on line N` for anything else. Variables already exported in the shell win. They use `JAVA_HOME` when it is JDK 21 or newer and otherwise fall back to `/opt/homebrew/opt/openjdk@21`.

## Checks

| Script | Verifies | Needs | Model calls |
| --- | --- | --- | --- |
| `checks/acceptance.py` | The shortener's HTTP behaviour: create, redirect, analytics, aliases, conflicts, invalid input, unknown codes | Shortener running (`SHORTENER_BASE_URL`, default `http://localhost:8080`) | none |
| `checks/agent_smoke.py` | All six scenarios replayed through the CLI reach their expected end state with a valid audit chain | `control-db`, Docker, the sandbox image, a warm `~/.m2` | none |
| `checks/web_smoke.py` | The operator API: request protections, clarification, stale-hash refusal, revision, approval, validation evidence, rejection | Factory running (`FACTORY_TEST_URL`, default `http://localhost:8000`), sandbox ready | none |
| `checks/browser_smoke.cjs` | The operator page in real Chrome, desktop and mobile; saves screenshots under `.runs/browser/` | Both applications, Node 22.12+, `puppeteer-core`, `CHROME_PATH` | none |
| `checks/clean_checkout.py` | `mvn clean test` passes in a fresh clone of the committed `HEAD`; writes `.runs/clean-checkout/<revision>/result.json` | JDK, Maven | none |
| `checks/verify_evidence.py` | Files under `docs/evaluation/samples/` match `manifest.json` exactly | Python | none |
| `checks/evaluate.py` | Runs `mvn -Pintegration test`, `agent_smoke.py`, `web_smoke.py` and `acceptance.py` in order; stores logs and `results.json` under `.runs/evaluation/<timestamp>/` | Everything above | none |
| `checks/live_tools_smoke.py --live` | All seven agent tools through the chat endpoint; saves a result under `.runs/tool-checks/` | Factory running with `ANTHROPIC_API_KEY` | **paid** |

Approvals made by `agent_smoke.py` and `evaluate.py` are recorded with the operator names `synthetic-fixture-test` and `synthetic-evaluation-test`.

Where each check fits is explained in the [testing strategy](../docs/04-quality/testing.md). Settings are listed in the [runbook](../docs/03-operations/runbook.md#settings).
