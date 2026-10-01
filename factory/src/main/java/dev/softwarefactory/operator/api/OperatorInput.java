package dev.softwarefactory.operator.api;

import java.util.Set;
import java.util.regex.Pattern;

/** Rejects unusable operator input before any decision is recorded; each message tells the operator what to do. */
final class OperatorInput {
    private static final Set<String> SCENARIOS = Set.of("greenfield", "brownfield", "ambiguous", "bugfix");
    private static final int MAX_ANSWER = 8000;
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");

    private OperatorInput() {}

    static void requireScenario(String name) {
        if (name == null || !SCENARIOS.contains(name)) throw new IllegalArgumentException("Unknown scenario");
    }

    static void requireReviewedHash(String hash) {
        if (hash == null || !HASH.matcher(hash).matches())
            throw new IllegalArgumentException("Review the current proposal before rejecting it");
    }

    static void requireAnswer(String answer) {
        if (answer == null || answer.isBlank() || answer.length() > MAX_ANSWER) {
            throw new IllegalArgumentException("Enter an answer in 1–" + MAX_ANSWER + " characters");
        }
    }

    static void requireFeedback(String feedback) {
        if (feedback == null || feedback.isBlank()) throw new IllegalArgumentException("Explain the changes you want");
    }
}
