package dev.shortener.analytics;

import java.util.List;

/** Applies coalesced redirect deltas to durable storage; throws when the batch did not commit. */
@FunctionalInterface
public interface RedirectStatsWriter {
    void write(List<RedirectDelta> deltas);
}
