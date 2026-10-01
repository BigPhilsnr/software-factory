package dev.softwarefactory.validation;

import java.time.Instant;

/** Whether Docker and the pinned validator image are usable now. */
public record Preflight(boolean ready, String detail, Instant checkedAt) {}
