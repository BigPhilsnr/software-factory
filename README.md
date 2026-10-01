# Software factory and URL shortener

**In one paragraph.** This repository holds two Spring Boot applications. The **shortener** is a small product: it creates short links, redirects visitors and counts visits. The **factory** is a control plane that changes that product under supervision: it takes an engineering request, lets Claude agents write documents and propose patches, applies the patches to an isolated copy of the code, runs the tests in a locked-down container, and stops for a human decision before anything is accepted. Recorded scenarios replay the whole workflow without calling a model, so everything except model quality can be checked locally and in CI.

## How the pieces fit

```mermaid
flowchart LR
    operator(["Operator"])
    visitor(["Client or visitor"])

    subgraph repo ["This repository"]
        factory["factory/<br/>control plane, port 8000"]
        shortener["shortener/<br/>product, port 8080"]
        scenarios["scenarios/<br/>recorded task graphs"]
    end

    controlDb[("control DB<br/>port 5434")]
    productDb[("shortener DB<br/>port 5433")]
    claude["Claude API"]
    sandbox["Docker sandbox<br/>offline Maven"]

    operator -->|"web UI, chat, CLI"| factory
    factory -->|"runs, audit"| controlDb
    factory -->|"live mode only"| claude
    factory -->|"tests a candidate copy"| sandbox
    scenarios -->|"fixture mode"| factory
    visitor -->|"HTTP"| shortener
    shortener --> productDb
```

*The factory never touches the running shortener or its database: it works on a Git worktree of the shortener's source.*

| Path | What it is |
| --- | --- |
| [`shortener/`](shortener/README.md) | The product. `POST /api/shorten`, `GET /{code}`, `GET /api/urls/{code}/analytics`. Contract in [`openapi.yaml`](shortener/openapi.yaml). |
| [`factory/`](factory/README.md) | The control plane: task graph, run engine, agents, sandbox validation, approvals, audit record, operator UI, chat and CLI. |
| [`scenarios/`](scenarios/README.md) | Six recorded scenarios (four that complete, two that must stop). |
| [`scripts/`](scripts/README.md) | Launchers and local checks. |
| [`docs/`](docs/README.md) | Overview, architecture, operations, quality and history, in reading order. |
| `build-config/` | PMD and SpotBugs rule files shared by both modules. |
| `.runs/`, `evidence/` | Created at run time and ignored by Git: candidate worktrees and immutable run outputs. |

## Quickstart (about five minutes)

You need JDK 21 or newer, Maven 3.9+, Python 3, Git and Docker with `docker-compose`. Ports 8000, 8080, 5433 and 5434 must be free.

```sh
# 1. Databases (both bind to 127.0.0.1 only)
docker-compose up -d shortener-db control-db

# 2. Build and unit-test both modules. This also fills ~/.m2, which the sandbox mounts read-only.
mvn -q test

# 3. Start the product (terminal 1)
python3 scripts/shortener.py

# 4. Check it over HTTP (terminal 2)
python3 scripts/checks/acceptance.py
curl -s http://localhost:8080/actuator/health/readiness
```

Then start the factory and replay a recorded scenario. No model key is needed for this.

```sh
# 5. Pull the pinned sandbox image once
docker pull maven@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320

# 6. Start the factory (terminal 3), then open http://localhost:8000/factory/
python3 scripts/factory_web.py
```

In the operator page open **Try a scenario**, keep the execution mode on **Fixture demo**, select **Create scenario run** and then **Start / resume**. Approve each proposal when the page asks for a decision. To replay all six scenarios from the command line instead:

```sh
python3 scripts/checks/agent_smoke.py
```

For live generation with Claude, copy `.env.example` to `.env`, set `ANTHROPIC_API_KEY` and `FACTORY_AUDIT_KEY`, and restart the factory. Live runs make paid API calls. See the [runbook](docs/03-operations/runbook.md) for every setting.

Stop the applications with Ctrl-C and the databases with `docker-compose down` (add `-v` only if you want to delete the data).

## What you can rely on, and what you cannot

- **Fixture mode is deterministic.** It replays recorded agent output but really applies patches, really runs the tests in Docker, and really enforces approvals, recovery and the audit record. It says nothing about model quality.
- **Live mode has been proven once.** One recorded live bug-fix run completed. Live greenfield and brownfield attempts failed. The evidence for both is in [`docs/evaluation/samples/`](docs/evaluation/samples/README.md).
- **Completing a run does not merge or deploy anything.** The result stays in an isolated worktree under `.runs/`.
- **This is a single-host, single-operator system.** The operator API listens on loopback and uses a page-issued token, not user accounts. See the [security model](docs/04-quality/security.md).

## Verify the code

```sh
mvn -f shortener/pom.xml verify     # unit tests + Spotless, PMD, SpotBugs, JaCoCo, enforcer
mvn -f factory/pom.xml verify
mvn -Pintegration verify            # adds the tests that need both databases and Docker
```

Use JDK 21 to 25 for `verify` (the bundled PMD cannot read JDK 26 class files). Details: [testing strategy](docs/04-quality/testing.md), [quality gate](docs/04-quality/quality-gate.md), [CI pipeline](docs/04-quality/ci.md).

## Where to go next

| If you want to | Read |
| --- | --- |
| Understand the idea and the vocabulary | [Overview](docs/01-overview/README.md), [glossary](docs/01-overview/glossary.md) |
| See how the factory works inside | [Factory architecture](docs/02-architecture/factory.md), then the [factory package guide](factory/README.md) |
| See how the shortener works inside | [Shortener architecture](docs/02-architecture/shortener.md), then the [shortener package guide](shortener/README.md) |
| Know why it was built this way | [Decision records](docs/02-architecture/decisions/README.md) |
| Run and operate it | [Runbook](docs/03-operations/runbook.md), [operator guide](docs/03-operations/operator-guide.md) |
| Judge the engineering quality | [Scorecard](docs/04-quality/scorecard.md), [security model](docs/04-quality/security.md), [risk register](docs/04-quality/risks.md) |
