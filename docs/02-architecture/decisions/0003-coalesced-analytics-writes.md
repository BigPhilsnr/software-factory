# ADR 0003: Coalesce redirect counts in memory and write them in batches

**Status:** accepted (replaces an earlier bounded queue of single writes)

**In one paragraph.** A redirect does not write to the database. It adds one to an in-memory counter for its link. Once a second, all pending counters are written in one batched `UPDATE`. Counts may lag by a second and may be lost in a crash; redirects are never slowed by analytics.

## Context

The first design queued one database write per redirect on a small worker pool. Under load the queue filled and writes were dropped, and a popular link caused many updates to the same row. An external review also found the redirect path waiting up to 100 ms for analytics.

## Decision

`CoalescingVisitRecorder`:

- `record(linkId)` is lock-free and never touches the database.
- A scheduled flush every `flush-interval` (1 s) drains the pending map and calls `JdbcRedirectStatsWriter`, which sends one batched `UPDATE link_stats SET redirect_count = redirect_count + ?, last_redirect_at = GREATEST(...)`. Rows are ordered by link id so two instances cannot deadlock.
- At most `max-pending-links` (10,000) links may be pending. Beyond that, visits are dropped and counted.
- A failed flush is merged back and retried at the next flush, within the same bound.
- On shutdown the recorder stops after the web server has drained and flushes one last time.

Meters: `shortener.analytics.recorded`, `.flushed`, `.dropped{reason}`, `.flush.failures`, `.pending`.

## Consequences

- A hot link costs one row update per second instead of one per visit.
- The analytics endpoint lags redirects by about one flush interval.
- A process crash loses up to one interval of counts. A long database outage with more than 10,000 active links loses the overflow.
- Counts are aggregate and approximate: fine for "how popular is this link", not for billing.

## Revisit when

- Counts must be exact or auditable: use a durable log or an outbox table.
- Per-visit attributes (time series, referrer, geography) are needed: aggregation in memory no longer fits.
