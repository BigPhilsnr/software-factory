package dev.softwarefactory.validation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

/** Proves or refutes a candidate by executing its tests somewhere it cannot harm the host. */
public interface CandidateValidator {
    /**
     * Runs the full suite. Every class in {@code requiredTestClasses} (fully qualified) must have produced
     * a report with at least one executed test, so changed tests cannot silently be skipped.
     *
     * @return a one-line summary of the passing run
     * @throws ValidationFailedException when the candidate fails reproducibly
     */
    String runSuite(Path candidate, Collection<String> requiredTestClasses) throws IOException, InterruptedException;

    /**
     * Confirms that exactly the named regression test fails by assertion on the current candidate.
     *
     * @return a one-line summary of the confirmed red test
     */
    String expectRegression(Path candidate, String testClass) throws IOException, InterruptedException;

    Preflight preflight();

    /**
     * Removes validator containers left by processes that no longer exist (or by this process when
     * {@code includeOwn}), e.g. after a crash.
     *
     * @return the number of containers removed
     */
    int sweepOrphanedContainers(boolean includeOwn);
}
