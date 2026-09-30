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
