# ADR 0007: HMAC audit chain with versioned hash schemes

**Status:** accepted

**In one paragraph.** Every run has a chain of audit events. Each event's hash is an HMAC-SHA256 over the previous hash, the event fields and the full run state, using a key that is not stored in the database. Rows written under the older keyless scheme stay verifiable because each row records which scheme it used.

## Context

The first chain used plain SHA-256 over `|`-separated fields. Anyone who could write to the database could change a row and recompute every later hash. Joining fields with a separator also made the boundaries between fields ambiguous. Existing chains could not simply be re-hashed, because that would itself be rewriting history.

## Decision

- `audit_events.hash_scheme`: `1` = legacy SHA-256, `2` = HMAC-SHA256. New events are always written with scheme 2 (`RunJournal.record`).
- Scheme 2 hashes length-prefixed fields: previous hash, sequence, timestamp, type, detail and state snapshot.
- The key comes from `FACTORY_AUDIT_KEY` (at least 32 characters). Without it, a development key is generated in `.runs/audit.key` with owner-only permissions and a warning is logged. Live runs refuse to start without a configured key.
- Verification (`AuditTrail.verify`) walks the chain in one repeatable-read transaction. It rejects unknown schemes and any step from scheme 2 back to scheme 1, and requires the newest snapshot to equal the stored run state.
- The engine verifies before every advance, approval and release; a failure safe-stops the run.

## Consequences

- A database writer without the key cannot alter or insert scheme-2 events undetected.
- Once a chain contains a scheme-2 event, every earlier legacy row is pinned by the keyed hash that follows it.
- **Not covered:** a chain that contains only legacy rows can still be rewritten by a database writer.
- **Not covered:** deleting the newest events and setting the run row back to the earlier snapshot leaves a chain that verifies. Nothing outside the database records how long the chain should be.
- **Not covered:** whoever holds both the database and the key (the host owner) can rewrite everything.
- There is one key and no rotation procedure. Changing the key makes existing scheme-2 chains fail verification.

## Revisit when

- An untrusted party can write to the control database: publish chain heads to an external, append-only store.
- Key rotation is needed: add a key id per row, in the same way the scheme is recorded today.
