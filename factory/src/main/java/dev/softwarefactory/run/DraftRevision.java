package dev.softwarefactory.run;

import dev.softwarefactory.candidate.DraftCheckpoint;
import dev.softwarefactory.candidate.GitWorkspace;
import dev.softwarefactory.governance.Hashes;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Retains only an unapproved draft whose source inputs survive a review revision unchanged. */
record DraftRevision(DraftCheckpoint checkpoint, String candidateDiff, Map<String, String> artifacts) {
    static DraftRevision capture(RunState state, String task, String specHash, GitWorkspace workspace)
            throws IOException, InterruptedException {
        DraftCheckpoint saved = state.drafting.get(task);
        if (!task.equals(state.pendingApprovalTask) || saved == null || !Hashes.same(specHash, state.specHash))
            return null;
        if (!Hashes.same(saved.inputHash(), PatchDrafting.inputHash(state, task, workspace))) return null;
        return new DraftRevision(
                saved,
                workspace.diff(Path.of(state.candidatePath), state.baselineCommit),
                Map.copyOf(state.artifactHashes));
    }

    void restore(RunState state, String task, GitWorkspace workspace) throws IOException, InterruptedException {
        if (!artifacts.equals(state.artifactHashes)
                || !candidateDiff.equals(workspace.diff(Path.of(state.candidatePath), state.baselineCommit))) return;
        state.drafting.put(
                task,
                new DraftCheckpoint(
                        checkpoint.directory(),
                        checkpoint.baseCommit(),
                        PatchDrafting.inputHash(state, task, workspace),
                        checkpoint.diffHash()));
        // DraftWorkspace.verify still checks the saved bytes and Git base before the next model call.
    }
}
