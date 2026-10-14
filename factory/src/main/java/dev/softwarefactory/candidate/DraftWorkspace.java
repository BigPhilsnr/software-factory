package dev.softwarefactory.candidate;

import dev.softwarefactory.governance.Hashes;
import dev.softwarefactory.governance.PatchHunks;
import dev.softwarefactory.governance.PolicyViolationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** A resumable scratch checkout. No operation here edits the run's approved candidate. */
public final class DraftWorkspace {
    private DraftCheckpoint identity;
    private final GitWorkspace git;
    private final List<String> scope;
    private final Save save;
    private final Check check;
    private final DraftFiles files;

    @FunctionalInterface
    public interface Save {
        void save(DraftCheckpoint checkpoint) throws IOException;
    }

    @FunctionalInterface
    public interface Check {
        String check(Path directory, String diff) throws IOException, InterruptedException;
    }

    public DraftWorkspace(DraftCheckpoint identity, GitWorkspace git, List<String> scope, Save save, Check check) {
        this.identity = identity;
        this.git = git;
        this.scope = List.copyOf(scope);
        this.save = save;
        this.check = check;
        this.files = new DraftFiles(path(), scope);
    }

    public static DraftCheckpoint create(Path candidate, Path directory, String inputHash)
            throws IOException, InterruptedException {
        String base = DraftRepository.create(candidate, directory);
        return new DraftCheckpoint(directory.toString(), base, inputHash, Hashes.sha256(""));
    }

    public Path path() {
        return Path.of(identity.directory());
    }

    public void verify() throws IOException, InterruptedException {
        DraftFiles.requireNoLinks(path().getParent(), path());
        if (!Files.isDirectory(path().resolve(".git")) || Files.isSymbolicLink(path().resolve(".git")))
            throw new PolicyViolationException("Draft Git metadata missing or unsafe");
        if (!Hashes.same(
                        identity.baseCommit(),
                        DraftRepository.git(path(), "rev-parse", "HEAD").strip())
                || !Hashes.same(identity.diffHash(), Hashes.sha256(diff())))
            throw new PolicyViolationException("Draft checkpoint integrity failed");
    }

    public String read(String name) throws IOException {
        return files.read(name);
    }

    public synchronized String write(String name, String expectedHash, String text)
            throws IOException, InterruptedException {
        String hash = files.write(name, expectedHash, text);
        checkpoint();
        return "Saved " + name + " sha256=" + hash;
    }

    public synchronized String replace(String name, String oldText, String newText)
            throws IOException, InterruptedException {
        String hash = files.replace(name, oldText, newText);
        checkpoint();
        return "Updated " + name + " sha256=" + hash;
    }

    public synchronized String delete(String name, String expectedHash) throws IOException, InterruptedException {
        files.delete(name, expectedHash);
        checkpoint();
        return "Deleted " + name;
    }

    /** Compatibility for scripted runtimes; live agents use individual file edits. */
    public synchronized void applyPatch(String patch) throws IOException, InterruptedException {
        git.apply(path(), PatchHunks.recount(patch), scope);
        checkpoint();
    }

    public String diff() throws IOException, InterruptedException {
        return git.diff(path(), identity.baseCommit());
    }

    public String check() throws IOException, InterruptedException {
        verify();
        return check.check(path(), diff());
    }

    private void checkpoint() throws IOException, InterruptedException {
        DraftCheckpoint next = new DraftCheckpoint(
                identity.directory(), identity.baseCommit(), identity.inputHash(), Hashes.sha256(diff()));
        save.save(next);
        identity = next;
    }
}
