package dev.softwarefactory.run;

import dev.softwarefactory.audit.EvidenceStore;
import java.time.Clock;

/** What every transition of a run shares: the store, its evidence, the integrity check and the failure policy. */
record RunCore(
        RunStore runs, RunEvidence evidence, EvidenceIntegrity integrity, FailureHandling failures, Clock clock) {
    static RunCore of(RunStore runs, EvidenceStore evidenceStore, Clock clock) {
        RunEvidence evidence = new RunEvidence(evidenceStore, runs);
        return new RunCore(
                runs,
                evidence,
                new EvidenceIntegrity(runs, evidence, clock),
                new FailureHandling(runs, evidence, clock),
                clock);
    }
}
