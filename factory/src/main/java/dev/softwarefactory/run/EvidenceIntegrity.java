package dev.softwarefactory.run;

import dev.softwarefactory.audit.EventTypes;
import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.platform.LogText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Refuses to build on a tampered record: a broken audit chain or altered evidence safe-stops the run. */
final class EvidenceIntegrity {
    private static final Logger LOG = LoggerFactory.getLogger(EvidenceIntegrity.class);

    private final RunStore runs;
    private final RunEvidence evidence;
    private final Clock clock;

    EvidenceIntegrity(RunStore runs, RunEvidence evidence, Clock clock) {
        this.runs = runs;
        this.evidence = evidence;
        this.clock = clock;
    }

    /**
     * @return true when the audit chain and every completed output are intact; otherwise the run has been
     *     safe-stopped
     */
    boolean verify(RunState state) throws IOException {
        String violation = violation(state);
        if (violation == null) return true;
        state.status = RunStatus.SAFE_STOPPED;
        state.finishedAt = clock.instant();
        runs.record(state, EventTypes.POLICY_SAFE_STOP, violation);
        LOG.warn("Run {} safe-stopped: {}", LogText.singleLine(state.id), LogText.singleLine(violation));
        return false;
    }

    private String violation(RunState state) throws IOException {
        if (!runs.auditValid(state.id)) return "Audit chain integrity failed";
        for (var entry : state.artifactHashes.entrySet()) {
            Path file = evidence.currentOutput(state, entry.getKey());
            if (!isUnalteredFile(file) || !Hashes.same(Hashes.sha256(Files.readAllBytes(file)), entry.getValue())) {
                return "Evidence integrity failed: " + entry.getKey();
            }
        }
        return null;
    }

    static boolean isUnalteredFile(Path file) {
        return Files.isRegularFile(file) && !Files.isSymbolicLink(file);
    }
}
