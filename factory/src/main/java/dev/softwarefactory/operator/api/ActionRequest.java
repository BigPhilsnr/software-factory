package dev.softwarefactory.operator.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** {@code POST /factory/api/runs/{id}/actions}: one operator action and the fields that action uses. */
public record ActionRequest(
        @NotBlank @Pattern(regexp = "advance|approve|reject|clarify|revise|budget")
        String action,

        String hash,
        String answer,
        String task,
        String feedback,
        Integer budget) {
    public int requiredBudget() {
        if (budget == null) throw new IllegalArgumentException("New total budget is required");
        return budget;
    }
}
