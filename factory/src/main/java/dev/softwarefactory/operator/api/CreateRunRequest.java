package dev.softwarefactory.operator.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** {@code POST /factory/api/runs}: a feature request (live) or a named scenario in fixture or live mode. */
public record CreateRunRequest(
        @NotBlank @Pattern(regexp = "feature|scenario") String kind,
        String requirement,
        String scenario,
        @Pattern(regexp = "fixture|live") String mode) {}
