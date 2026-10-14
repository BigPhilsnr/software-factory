package dev.softwarefactory.generation.tools;

import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import dev.softwarefactory.candidate.DraftCheckFailedException;
import dev.softwarefactory.candidate.DraftWorkspace;
import java.util.List;
import java.util.stream.Stream;

/** Extra capabilities given only to PATCH agents, confined to their scratch workspace. */
public final class DraftTools {
    private final DraftWorkspace draft;
    private final ToolSession session;

    public DraftTools(DraftWorkspace draft, ToolSession session) {
        this.draft = draft;
        this.session = session;
    }

    public List<FunctionTool> declarations() {
        return Stream.of("readDraft", "writeDraft", "replaceDraft", "deleteDraft", "checkDraft")
                .map(method -> FunctionTool.create(this, method))
                .toList();
    }

    @Schema(
            name = "read_draft_file",
            description =
                    "Read an editable draft file with its SHA-256, or ABSENT. Output is bounded; use read_file for line ranges.")
    public String readDraft(@Schema(name = "path") String path) {
        return session.invoke("read_draft_file", path, () -> draft.read(path));
    }

    @Schema(
            name = "write_draft_file",
            description =
                    "Create or replace one scoped draft file. Supply ABSENT for a new file, otherwise the SHA-256 from read_draft_file. Use small complete files; at most 256 KiB. Does not edit the approved candidate.")
    public String writeDraft(
            @Schema(name = "path") String path,
            @Schema(name = "expected_hash") String expectedHash,
            @Schema(name = "content") String content) {
        return session.invoke(
                "write_draft_file", path + expectedHash + content, () -> draft.write(path, expectedHash, content));
    }

    @Schema(
            name = "replace_draft_text",
            description =
                    "Replace one exact nonempty, uniquely occurring text fragment in a scoped draft file. Use this for small edits to existing files.")
    public String replaceDraft(
            @Schema(name = "path") String path,
            @Schema(name = "old_text") String oldText,
            @Schema(name = "new_text") String newText) {
        return session.invoke(
                "replace_draft_text", path + oldText + newText, () -> draft.replace(path, oldText, newText));
    }

    @Schema(
            name = "delete_draft_file",
            description = "Delete one scoped draft file only if its SHA-256 matches the supplied version.")
    public String deleteDraft(@Schema(name = "path") String path, @Schema(name = "expected_hash") String expectedHash) {
        return session.invoke("delete_draft_file", path + expectedHash, () -> draft.delete(path, expectedHash));
    }

    @Schema(
            name = "check_draft",
            description =
                    "Run trusted draft checks and, when this patch directly precedes validation, sandbox tests. Returns compiler/test feedback to repair. At most three distinct checks per drafting attempt. Does not approve or release code.")
    public String checkDraft() {
        return session.invoke("check_draft", "", () -> {
            try {
                return draft.check();
            } catch (DraftCheckFailedException invalid) {
                return "CHECK_FAILED\n" + invalid.getMessage();
            }
        });
    }
}
