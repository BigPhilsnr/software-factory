# Local operations runbook

## Check service health

Call `/actuator/health/liveness` and `/actuator/health/readiness`. Readiness requires PostgreSQL. A ready response does not prove that analytics writes or the local limiter are shared across nodes.

## Redirects return 429

Creation throttling applies only to `POST /api/shorten`. If `GET /{code}` returns 429, treat it as a regression. Reproduce by issuing more than 30 redirects to one known code in a minute; `RedirectRateLimitRegressionTest` captures this defect. Do not widen the limiter to redirect requests as an emergency fix.

## Redirects work but analytics stops increasing

Check the `shortener.analytics.failures` meter and PostgreSQL availability. Analytics uses a separate pool with two connections and a bounded work queue. A timed-out update may still have committed, so reconcile against database state before interpreting dropped-update counts as exact lost-click counts. Redirect correctness takes priority over the counter.

## Database unavailable

Uncached codes return a controlled `503`. A recently cached immutable code may still redirect for up to 60 seconds, with analytics possibly unavailable. Restore PostgreSQL, then verify readiness, create, redirect, and analytics with `scripts/acceptance.py`.

## Rollback

Use the reviewed Git tag for code rollback. Database migrations in this prototype are additive. Do not assume a Git reset reverses stored data; test against disposable local databases before applying a migration.
