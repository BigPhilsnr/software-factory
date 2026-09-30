# Agentic URL shortener

This repository contains a runnable URL shortener and a Java control plane that plans and replays four engineering scenarios against isolated Git candidates. The control plane uses Google ADK for **live** Claude calls and recorded fixtures for deterministic local replay. The fixture runs exercise real patch application, sandboxed tests, approvals, recovery, and audit transitions; they are not evidence of live AI generation.

## Repository map

| Path | Purpose |
| --- | --- |
| `orchestrator/` | Task DAG, durable run state, ADK chat, browser operator page, policy, approvals, sandbox validator, CLI |
| `shortener/` | Spring Boot API, OpenAPI contract, domain rules, PostgreSQL persistence, Flyway schema, unit tests |
| `scenarios/` | Greenfield, brownfield, ambiguous, seeded bug-fix, and negative-control fixtures |
| `scripts/` | Reproducible agent replay and independent HTTP acceptance check |
| `docs/PLAN.md` | Assignment interpretation and target engineering gates |
| `docs/AGENT_SYSTEM.md` | Agent architecture, tested behavior, and remaining limits |
| `docs/DECISIONS.md` | Rationale and trade-offs for the control plane, sandbox, and product |
| `docs/RUNBOOK.md` | Local operational and regression guidance |

## Run locally

Prerequisites: JDK 21, Maven 3.9+, Python 3, Docker with a running daemon, and `docker-compose` (the standalone command). On this Mac, JDK 21 is at `/opt/homebrew/opt/openjdk@21`; set `JAVA_HOME` to your JDK 21 path. Ports 8080, 5433, and 5434 must be free. The Compose credentials are local demo credentials.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
docker-compose up -d shortener-db control-db
mvn -q -f pom.xml test
mvn -q -f shortener/pom.xml spring-boot:run
```

In another terminal:

```sh
python3 scripts/acceptance.py
curl -s http://localhost:8080/actuator/health/readiness
```

The acceptance check creates a link, verifies its `302` redirect and analytics, tests aliases and conflicts, rejects an invalid URL, and checks an unknown code. The service runs with PostgreSQL on port 5433 and the control plane uses a separate PostgreSQL database on port 5434. Stop the service with Ctrl-C; stop the databases with `docker-compose down` (omit `-v` to retain local data).

## Replay and inspect the agent system

To use the browser interface, start the control database, then run:

```sh
docker-compose up -d control-db
python3 scripts/factory_web.py
```

Open **[the factory operator page](http://localhost:8000/factory/)** for feature requests, run progress, artifacts, clarification forms and approval buttons. **[ADK chat](http://localhost:8000/dev-ui/?app=software_factory)** connects to the same factory; ask questions about the shortener directly, or send `/help` to see its commands. Chat retains recent follow-ups and uses paid model calls. Use `/feature REQUIREMENT` to explicitly create a run; ordinary conversation never creates one. The launcher loads the ignored `.env` file and uses JDK 21. Keep the process running while using the browser.

New feature requests create a live workflow against `url-v3`; generation begins when you select **Start / resume**. Each implementation/test patch pauses for review, followed by sandbox validation and final release review. **Try a scenario → Fixture demo** exercises the controls without paid model calls. The dependency cache and validator image below are also required for browser-triggered validation. See [UI usage and limits](docs/WEB_UI.md).

Warm Maven's local dependency cache and pull the validator image once, then run the full fixture smoke test:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
docker-compose up -d control-db
mvn -q -f pom.xml test
docker pull maven:3.9-eclipse-temurin-21
python3 scripts/agent_smoke.py
```

`agent_smoke.py` labels every approval `synthetic-fixture-test`. It runs greenfield, brownfield, ambiguous, and seeded bug-fix scenarios to completion, then checks policy safe-stop and retry exhaustion. It executes candidate Maven tests in a network-disabled, resource-limited container and verifies each audit chain. Each run creates an ignored `.runs/<run-id>/` candidate and `evidence/<run-id>/` artifacts.

For a manual replay, use the operator CLI. It prints JSON run state, including any pending approval hash:

```sh
mvn -q -f orchestrator/pom.xml exec:java -Dexec.args='start scenarios/greenfield.json fixture'
mvn -q -f orchestrator/pom.xml exec:java -Dexec.args='advance <run-id>'
mvn -q -f orchestrator/pom.xml exec:java -Dexec.args='review <run-id>'
mvn -q -f orchestrator/pom.xml exec:java -Dexec.args='approve <run-id> <reviewed-hash>'
mvn -q -f orchestrator/pom.xml exec:java -Dexec.args='advance <run-id>'
mvn -q -f orchestrator/pom.xml exec:java -Dexec.args='verify-audit <run-id>'
```

The ambiguous scenario pauses for `clarify <run-id> <answer>` before planning. `revise <run-id> <task-id> [feedback-file]` invalidates affected descendants and optionally records review feedback for the next model attempt. Patch proposals must pass a read-only Git applicability check before approval. `reject <run-id>` ends a pending approval as `NOT_APPROVED`. `review` gives the immutable proposed patch or candidate diff path, scope, baseline commit, and exact hash to approve. The CLI is a local prototype, not an authenticated multi-user approval service.

For a live ADK run, copy `.env.example` to the ignored `.env` file, replace `ANTHROPIC_API_KEY` with your key, and start with `python3 scripts/factory_cli.py start scenarios/bugfix.json live`. Then use `python3 scripts/factory_cli.py advance <run-id>` and `review <run-id>`. Use the same launcher for later `approve` and `advance` commands so the key remains available to model calls. You can instead export `ANTHROPIC_API_KEY` in the launching shell. `CLAUDE_MODEL` and `FACTORY_MAX_MODEL_CALLS` are optional. Live mode makes paid provider calls; the release gate requires review of the exact candidate diff and hash. The fixture suite is the reproducible evaluation path.

A live Claude Sonnet 5 bug-fix run completed after exact-hash release approval, with a verified red regression and five passing candidate tests. Its repair remains in an isolated candidate worktree for review or integration. Live greenfield generation remains less constrained: one trial chose a different stack and did not produce an acceptable patch. See [the agent-system notes](docs/AGENT_SYSTEM.md) for the run evidence and limits.

## Product contract

`POST /api/shorten` accepts `{"url":"https://example.com","alias":"optional-alias"}` and returns a code and short URL. `GET /{code}` returns a `302` to the target. `GET /api/urls/{code}/analytics` returns aggregate redirect count. Creation is limited to 30 requests per source IP per 60-second fixed window; redirects are never creation-throttled. Links are immutable, with a bounded 60-second in-process lookup cache. Analytics uses a bounded background recorder and may lag or drop writes under failure; redirect availability takes priority.

The code follows the versioned path `project-start` → `url-v1` → `url-v2` → `url-v3`. `url-v2-buggy` is an intentionally seeded redirect-throttling defect and `url-v2-fixed` contains its regression test and repair. See [the agent-system notes](docs/AGENT_SYSTEM.md) for evidence and limitations.
The endpoint and response schema is in [the OpenAPI file](shortener/openapi.yaml).
