package dev.softwarefactory.run;

import dev.softwarefactory.validation.CandidateValidator;
import dev.softwarefactory.validation.Preflight;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Stands in for the Docker sandbox: answers with a scripted report or failure and records what it was asked. */
final class ScriptedValidator implements CandidateValidator {
    @FunctionalInterface
    interface Outcome {
        String report() throws IOException, InterruptedException;
    }

    final List<Collection<String>> requiredTestClasses = new ArrayList<>();
    final List<String> regressionClasses = new ArrayList<>();
    private Outcome outcome =
            () -> "Sandboxed Maven tests passed: executed=3, failures=0, errors=0, skipped=0, suites=1";

    void answers(Outcome next) {
        this.outcome = next;
    }

    @Override
    public String runSuite(Path candidate, Collection<String> required) throws IOException, InterruptedException {
        requiredTestClasses.add(required);
        return outcome.report();
    }

    @Override
    public String expectRegression(Path candidate, String testClass) throws IOException, InterruptedException {
        regressionClasses.add(testClass);
        return outcome.report();
    }

    @Override
    public Preflight preflight() {
        return new Preflight(true, "scripted", Instant.EPOCH);
    }

    @Override
    public int sweepOrphanedContainers(boolean includeOwn) {
        return 0;
    }
}
