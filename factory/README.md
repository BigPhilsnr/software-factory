# Software factory

This module takes an engineering request through planning, generated patches, review, sandbox tests and release approval. It operates on isolated candidates; it does not apply generated changes to this checkout.

Start with `src/main/java/dev/softwarefactory/`:

| Package | Responsibility and starting point |
| --- | --- |
| `operator/web/` | Browser API, ADK chat and operator actions. Start at `FactoryWebServer`, then `FactoryService`. `ChatConversation` answers with read-only tools; `OperatorCommands` routes explicit commands. |
| `operator/cli/` | The same workflow through `FactoryCli`. |
| `workflow/` | Run/task state and transitions. Read `RunEngine`, `TaskGraph`, then `RunState`. |
| `workflow/scenario/` | Scenario contracts and compatibility with persisted scenario paths. |
| `agents/` | Live ADK/Claude generation and recorded fixture generation through `AgentRuntime`. |
| `agents/tools/` | Shared tool catalog, bounded source/Git reads, public web reading/search, request limits and tool audit events. Start at `EngineeringTools`. |
| `governance/` | Deterministic patch approval rules and hashes. |
| `execution/` | Detached Git workspaces, patch scope enforcement and container validation. |
| `persistence/` | `RunStore` workflow authority boundary; PostgreSQL storage, leases and audit events in `ControlRepository`. |
| `evidence/` | Immutable, versioned engineering outputs. |
| `observability/` | Reliability measurements derived from the complete audit timeline. |
| `serialization/` | Shared JSON configuration. |

The browser files live together in `src/main/resources/static/factory/`. Tests mirror the Java packages under `src/test/java/dev/softwarefactory/`.

Request flow: operator → workflow → generation → governance → candidate execution → persisted evidence → operator review. `RunEngine` coordinates these collaborators; this package organization does not claim that every dependency has been inverted into a separate interface.

From the repository root, run `python3 scripts/factory_web.py`. See the [operator guide](../docs/operations/operator-guide.md) and [agent architecture](../docs/architecture/agent-system.md).

## Spring runtime

The web application has an explicit Spring Boot composition root in `operator/web/FactoryWebServer.java`. Spring owns the service lifecycle, ADK loader, control connection pool, migrations, security chain and health endpoints. See [platform decisions](../docs/architecture/spring-platform.md) for library choices and the local-only operator boundary.

## HTTP integration tests

`operator/web/FactoryHttpIntegrationTest` uses REST Assured against a real Spring Boot server on a random port. It exercises MVC, security, the factory service, asynchronous orchestration, PostgreSQL audit/state persistence, Git candidates and actual Docker validation. Recorded fixtures replace only model generation; no approval, persistence or validator is mocked.

The suite checks operator-token/origin restrictions, request validation, ADK agent registration, clarification, stale approval hashes, revision and rejection, evidence tampering, and separate patch/release approvals. The happy path reaches completion only after candidate tests pass. Every run asserts zero model calls and a valid audit chain. Surefire clears `ANTHROPIC_API_KEY` in its test JVM, and the suite verifies that live mode is unavailable.

From the repository root, with JDK 21, Git, Docker/Colima and the control database running:

```sh
mvn -pl factory -Pintegration -Dtest=FactoryHttpIntegrationTest test
```

The test owns a unique database schema and a disposable Git clone under `.runs/http-integration-*`; it copies current scenario files into that clone. Background workers drain before cleanup removes these resources. Existing operator runs and source files are not modified. `CONTROL_DB_URL`, `CONTROL_DB_USER`, and `CONTROL_DB_PASSWORD` override local Compose defaults; Maven does not load `.env`. Port 8000 need not be running.

The Docker validator requires the pinned Maven image and populated local Maven cache described in the [root test instructions](../README.md#rest-assured-integration-tests). These tests exercise orchestration and boundaries, not live model reasoning quality. Existing `RunEngineResilienceTest` and `ControlRepositoryIntegrationTest` additionally cover recovery, concurrent leases, migration compatibility and persisted budgets.
