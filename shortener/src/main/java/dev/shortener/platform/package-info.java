/**
 * Cross-cutting plumbing that is not part of the link's story: the validated settings every package reads and
 * the beans they all share. Nothing here may depend on {@code shorten}, {@code redirect}, {@code analytics}
 * or {@code link}.
 */
package dev.shortener.platform;
