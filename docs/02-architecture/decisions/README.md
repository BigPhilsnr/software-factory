# Decision records

**In one paragraph.** Each file records one decision: the situation that forced a choice, what was chosen, what follows from it (good and bad), and the condition under which it should be looked at again. Read the ones that concern the part you are changing. If you reverse a decision, add a new record and mark the old one superseded; do not edit history.

## Index

| # | Decision | Area |
| --- | --- | --- |
| [0001](0001-plain-sql-over-spring-data-jpa.md) | Plain SQL (JdbcClient and JDBC) instead of Spring Data JPA | Both |
| [0002](0002-hand-rolled-rate-limiter-and-caffeine-cache.md) | A small in-process rate limiter and a Caffeine cache, not Resilience4j or Redis | Shortener |
| [0003](0003-coalesced-analytics-writes.md) | Coalesce redirect counts in memory and write them in batches | Shortener |
| [0004](0004-two-hikari-connection-pools.md) | Two connection pools in the shortener | Shortener |
| [0005](0005-flyway-for-both-databases.md) | Flyway migrations for both databases | Both |
| [0006](0006-advisory-lock-run-leases.md) | PostgreSQL advisory locks as run leases | Factory |
| [0007](0007-hmac-audit-chain-with-versioned-schemes.md) | HMAC audit chain with versioned hash schemes | Factory |
| [0008](0008-forced-private-index-release-diff.md) | Compute the release diff through a private Git index with forced add | Factory |
| [0009](0009-trusted-validation-pom-and-offline-sandbox.md) | A trusted validation POM and an offline, locked-down sandbox | Factory |
| [0010](0010-operator-token-on-state-changing-chat-commands.md) | Require the operator token for chat commands that change a run | Factory |
| [0011](0011-custom-thinking-aware-claude-adapter.md) | A custom Claude adapter instead of ADK's built-in model class | Factory |
| [0012](0012-story-packages-enforced-by-archunit.md) | Packages named after the story, with dependencies enforced by ArchUnit | Both |
| [0013](0013-local-quality-gate-instead-of-sonar-server.md) | A local quality gate in Maven instead of a Sonar server | Build |
| [0014](0014-spring-boot-platform-and-deferred-libraries.md) | Spring Boot as the platform, and which libraries were left out | Both |
| [0015](0015-agents-propose-deterministic-code-decides.md) | Agents propose inert text; deterministic code decides and acts | Factory |
| [0016](0016-separate-control-database-and-worktree-candidates.md) | A separate control database, and Git worktrees as candidates | Factory |

## Format

Every record has the same sections:

| Section | Content |
| --- | --- |
| Status | `accepted`, or `superseded by NNNN` |
| In one paragraph | The decision in plain words |
| Context | What made a decision necessary, and the options considered |
| Decision | What the code does, with class names |
| Consequences | What this buys and what it costs, including what is not covered |
| Revisit when | The concrete trigger for reconsidering |

To add a record, copy the newest file, take the next number, and add a row to the index.
