/**
 * Chapter two: a visitor follows the short link. {@link dev.shortener.redirect.ResolveLink} finds the link
 * (through a bounded cache in front of PostgreSQL) and hands the visit to a
 * {@link dev.shortener.redirect.VisitRecorder}. Depends only on {@code link} and {@code platform}.
 */
package dev.shortener.redirect;
