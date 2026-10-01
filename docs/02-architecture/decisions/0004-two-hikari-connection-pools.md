# ADR 0004: Two connection pools in the shortener

**Status:** accepted

**In one paragraph.** The shortener has a primary Hikari pool for requests and a separate two-connection pool used only by analytics flushes. A slow or stuck flush can exhaust its own pool but can never take a connection away from a redirect.

## Context

Lookups and counter writes compete for the same database. With one pool, a burst of slow writes would hold connections and make redirects wait or fail. Redirects are the product; counters are a side effect.

## Decision

- Primary pool: Spring Boot's auto-configured `HikariDataSource`, 8 connections, 1 s connection timeout (fail fast with `503` rather than queue).
- Analytics pool: built in `AnalyticsConfiguration` from the same URL, credentials and driver properties (so it inherits the socket, statement and lock timeouts), named `analytics-pool`, size `shortener.analytics.pool-size` (default 2, at most 16), 1 s connection timeout, 2 s query timeout.
- The pool is wrapped in an `AnalyticsPool` holder so Spring does not see a second `DataSource` bean and Flyway, health checks and `JdbcClient` keep using the primary.
- Analytics reads (`GET .../analytics`) use the primary pool. The second pool is reserved for writes.

## Consequences

- This is a bulkhead: analytics trouble is contained.
- Each instance holds up to 10 database connections.
- `AnalyticsPoolIntegrationTest` checks that the second pool really inherits the timeouts.

## Revisit when

- Many instances share one database and connection count becomes the limit: put a pooler in front or shrink the pools.
- Analytics moves to its own store.
