package dev.softwarefactory.operator.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Identifies the immutable rejected proposal to recount and submit for review. */
public record PatchRecoveryRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]+") String task,
        @Min(1) int version,
        @NotNull @Pattern(regexp = "[a-f0-9]{64}") String hash) {}
