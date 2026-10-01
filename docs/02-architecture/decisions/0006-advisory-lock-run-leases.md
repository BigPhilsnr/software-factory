# ADR 0006: PostgreSQL advisory locks as run leases

**Status:** accepted

**In one paragraph.** Before a process changes a run it takes a session-level PostgreSQL advisory lock for that run on a dedicated connection, and releases it when the transition ends. A second process gets an immediate conflict. A revision check on the run row backs this up.

## Context

The web server's background workers and the CLI can act on the same run. One transition can last minutes, because it may include model calls and a sandboxed build. Two processes applying patches to the same worktree would corrupt it.

Options considered:

| Option | Why not |
| --- | --- |
| Hold a row lock (`FOR UPDATE`) for the whole transition | Keeps a transaction open for minutes and blocks every reader that needs the row lock. |
| A lease table with expiry timestamps | Needs heartbeats and clock agreement, and a crashed holder blocks the run until expiry. |
| An in-process lock | Does not cover the CLI, which is a separate process. |

## Decision

- `RunLeases.acquire(id)` opens a connection and calls `pg_try_advisory_lock(0x46414354, hash)`, where the first key is a fixed namespace ("FACT") and the second is the 32-bit hash of the run's UUID. It never waits.
- `RunLease.close()` calls `pg_advisory_unlock` and returns the connection. If lock or unlock fails in an uncertain way, the physical connection is aborted rather than returned to the pool.
- Independently, every write presents the revision it loaded (`RunJournal.record`); a stale writer is refused.
- `RunEngine.underLease` wraps `advance`, `approve`, `clarify` and `revise`.

## Consequences

- If the holder dies, PostgreSQL releases the lock with its session. No expiry logic is needed.
- The second key has 32 bits, so two different runs can collide. A collision makes an unrelated run report "already being advanced" for a moment. It can never let two processes own the same run.
- Each in-flight transition holds one connection. The web pool has 8 and at most two runs advance at once.
- Session-level locks do not survive a transaction-mode connection pooler such as PgBouncer.
- The mechanism is PostgreSQL-specific.

## Revisit when

- Runs are scheduled across several hosts: a queue with visibility timeouts or a real workflow engine fits better.
- A transaction-mode pooler is introduced.
