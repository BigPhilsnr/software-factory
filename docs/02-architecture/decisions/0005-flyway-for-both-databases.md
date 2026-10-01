# ADR 0005: Flyway migrations for both databases

**Status:** accepted

**In one paragraph.** Both schemas are defined by versioned SQL files under `src/main/resources/db/migration/` and applied by Flyway at startup. Nothing generates schema from code. The factory's first migration adopts a database that existed before Flyway without touching its data.

## Context

The control schema was first created by `CREATE TABLE IF NOT EXISTS` statements in application code. That gave no version, no ordering and no record of what had run. The control database also holds hash-chained audit rows whose bytes must never be rewritten by a schema change.

## Decision

- Shortener: `V1__links.sql`, `V2__link_constraints.sql`.
- Factory: `V1__control_authority.sql` is additive (`IF NOT EXISTS` on every table and column) and Flyway runs with `baseline-on-migrate` at version 0. An old database is baselined, then V1 fills in whatever is missing. `V2__audit_scheme_and_indexes.sql` adds `hash_scheme` (default 1, the legacy scheme) and two indexes.
- The web server lets Spring Boot run Flyway before the control beans are created (`@DependsOnDatabaseInitialization`). The CLI runs the same migrations itself in `ControlDatabase.open`, so both entry points agree on the schema.

## Consequences

- Schema history is in the `flyway_schema_history` table and in Git.
- Migrations are forward-only. A mistake is fixed by a new migration, not by editing an old one.
- Rolling back code does not roll back the schema. Test migrations on a disposable database first.
- Integration tests create a fresh schema per suite and run the real migrations.

## Revisit when

- Zero-downtime upgrades across several instances are needed: adopt an expand-then-contract discipline for each change.
