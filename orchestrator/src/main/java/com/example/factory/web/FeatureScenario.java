package com.example.factory.web;

import com.example.factory.domain.*;
import java.util.List;

/** Trusted workflow structure; the user supplies requirements, never executable scenario policy. */
final class FeatureScenario {
    private FeatureScenario() {}

    static ScenarioSpec create(String requirement) {
        if (requirement == null || requirement.isBlank() || requirement.length() > 8000) {
            throw new IllegalArgumentException("Describe a feature in 1–8000 characters");
        }
        return new ScenarioSpec("feature-request", requirement.strip(), "url-v3", List.of(
            task("understand", Stage.REQUIREMENTS, List.of(), TaskKind.ARTIFACT, "requirements",
                "Inspect the existing Java 21 Spring Boot shortener. Define measurable criteria for the requested feature. Preserve existing behavior and APIs unless explicitly requested. Keep the prototype scope small; state assumptions. Maximum 800 words.", List.of(), false),
            task("plan", Stage.PLANNING, List.of("understand"), TaskKind.ARTIFACT, "planner",
                "Produce a concise implementation plan with exact file paths, contracts and independent test cases. Preserve Java, Spring Boot, PostgreSQL and existing dependencies. Fit the feature in one small implementation patch and one test patch. Maximum 1000 words.", List.of(), false),
            task("implementation", Stage.IMPLEMENTATION, List.of("plan"), TaskKind.PATCH, "implementer",
                "Implement the requested feature with a minimal patch to production code and API documentation only. Preserve compatibility. Include no test changes in this step. Use accurate unified diff hunk counts; no Markdown fences. Do not weaken security or validation.", List.of("shortener/src/main", "shortener/openapi.yaml"), true),
            task("tests", Stage.IMPLEMENTATION, List.of("plan", "implementation"), TaskKind.PATCH, "test_author",
                "Add executable JUnit tests independently verifying the feature contract and regressions against the candidate sources. Do not weaken or delete existing assertions. Return a small valid unified diff without Markdown fences.", List.of("shortener/src/test"), true),
            task("validate", Stage.VALIDATION, List.of("tests"), TaskKind.VALIDATE, "validator", "Run isolated candidate tests.", List.of(), false),
            task("documentation", Stage.DOCUMENTATION, List.of("validate"), TaskKind.ARTIFACT, "documentation", "Summarize the feature, test evidence and limitations in at most 500 words. Do not claim release approval.", List.of(), false),
            task("release", Stage.RELEASE, List.of("documentation"), TaskKind.RELEASE, "release", "Review the validated candidate.", List.of(), true)
        ));
    }

    private static TaskSpec task(String id, Stage stage, List<String> dependencies, TaskKind kind, String role,
                                 String prompt, List<String> scope, boolean approval) {
        return new TaskSpec(id, stage, dependencies, kind, role, prompt, null, List.of("FEATURE"), scope, approval);
    }
}
