/**
 * Chapter three: the visit is counted. Visits are coalesced in memory and flushed in batches over a dedicated
 * connection pool, so counting can never slow a redirect; {@code GET /api/urls/{code}/analytics} reads the
 * totals back. Depends on {@code redirect} (whose visits it records), {@code link} and {@code platform}.
 */
package dev.shortener.analytics;
