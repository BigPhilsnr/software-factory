# ADR 0001: Plain SQL (JdbcClient and JDBC) instead of Spring Data JPA

**Status:** accepted

**In one paragraph.** Both applications talk to PostgreSQL with hand-written SQL. The shortener uses Spring's `JdbcClient`; the factory uses plain `java.sql` with explicit transactions. No JPA, no Hibernate, no generated schema.

## Context

The shortener has two tables and immutable rows. The factory has four tables, and two of them hold opaque JSON or hash-chained events. Several operations depend on specific PostgreSQL statements:

- creating a link and its counter row in one statement (a CTE with `INSERT ... RETURNING`),
- a batched counter `UPDATE` with `GREATEST(...)`,
- `SELECT ... FOR UPDATE` on the run row, then an insert and an update in the same transaction,
- `INSERT ... ON CONFLICT DO UPDATE ... WHERE ... RETURNING` for the chat budget,
- session-level advisory locks that must stay on one physical connection.

## Decision

- Shortener: `JdbcClient` with named parameters and small `RowMapper` lambdas (`JdbcLinkRepository`, `JdbcRedirectStatsReader`); `JdbcTemplate.batchUpdate` for analytics flushes (`JdbcRedirectStatsWriter`).
- Factory: `ControlDatabase` hands out connections and wraps work in `query(...)` or `transaction(...)`. `RunJournal`, `AuditTrail`, `RunLeases` and `ChatLedger` write their SQL directly.
- The schema is owned by Flyway ([ADR 0005](0005-flyway-for-both-databases.md)).

## Consequences

- Every statement that runs is visible in the source. Transaction boundaries are explicit, which matters for the "state and audit event commit together" rule.
- There is no persistence context: no lazy loading, no dirty checking, no flush order to reason about.
- Row mapping is written by hand. With six tables this is a few dozen lines.
- The SQL is PostgreSQL-specific. Moving to another database would mean rewriting it.

## Revisit when

- The domain grows a real object graph (many related entities loaded and saved together).
- A second database engine must be supported.
