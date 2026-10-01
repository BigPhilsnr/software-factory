# ADR 0002: A small in-process rate limiter and a Caffeine cache, not Resilience4j or Redis

**Status:** accepted

**In one paragraph.** The shortener limits link creation with its own 120-line fixed-window limiter and caches link lookups with Caffeine. Both live in the process. This is correct for one instance and must be replaced before running several.

## Context

Two needs: stop one client from flooding `POST /api/shorten`, and keep `GET /{code}` fast and available. The documented deployment is a single instance reached directly.

Options considered:

| Option | Why not (now) |
| --- | --- |
| Resilience4j `RateLimiter` | It limits a named resource, not each client. Per-client limiting would need one limiter instance per address plus our own bounded eviction, which is most of the work anyway. Its retry and circuit-breaker modules are not wanted around non-idempotent operations. |
| Redis (rate limit and cache) | A new network dependency on the hot path, with its own failure modes, for a workload that fits in one process. |
| Hand-written LRU map for the cache | An earlier version did this. Size bounds, time-based expiry and statistics are exactly what Caffeine already does well. |

## Decision

- `CreationRateLimiter`: fixed windows keyed by client network (IPv4 address, IPv6 /64), updated with `ConcurrentMap.compute`, bounded by `max-tracked-clients`. When the table is full, expired windows are swept once per window; new clients then share a /16 or /48 bucket, and beyond twice the bound one overflow bucket. Memory is strictly bounded and nobody is rejected only because the table is full.
- `LinkCache`: two Caffeine caches. Links: 10,000 entries, 60 s. Known-missing codes: 2,000 entries, 2 s, kept separate so a scan of unknown codes cannot evict real links. Both publish Micrometer statistics.
- The cache hides behind `LinkRepository` (`CachedLinkRepository` is the primary bean), so no use case knows it exists.

## Consequences

- No extra service to run. Redirects for cached links survive a short database outage.
- Fixed windows allow up to twice the limit across a window boundary. This is documented in the API contract.
- State is per process. With N instances the effective quota is N times higher and caches differ.
- The limiter keys on the socket peer. Behind a proxy it sees only the proxy unless forwarded headers are explicitly trusted.

## Revisit when

- A second instance is deployed: move the limiter (and probably the cache) to a shared store.
- Links become mutable or deletable: a 60-second cache would then serve stale targets.
