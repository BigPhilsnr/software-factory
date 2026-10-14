# ADR 0018: Incremental drafting and bounded repair

**Status:** accepted; partially supersedes ADR 0015's text-only PATCH generation.

**In one paragraph.** Live PATCH agents edit a separate, durable draft in small steps. Git produces the proposal from those edits. Bounded validation feedback lets the agent repair ordinary implementation errors before review, while the existing exact-hash approval and candidate validation gates remain authoritative.

## Context

A single response containing a large unified diff can exhaust the provider output limit, including reasoning tokens, without producing a usable artifact. Retrying the same large response discards progress. Provider text saying “completed” also cannot establish that a feature was applied or validated.

## Decision

- `PatchDrafting` snapshots the current candidate into an independent Git repository under `.runs/drafts/`. The agent never writes directly to the approval candidate. Fixture generation remains deterministic.
- ADK `FunctionTool` exposes `read_draft_file`, `write_draft_file`, `replace_draft_text`, `delete_draft_file` and `check_draft` only to live PATCH workers. Chat and document workers retain read-only tools. There is no arbitrary shell tool.
- `DraftFiles` confines edits to task scope, rejects traversal, symlinks and credential paths, limits UTF-8 files to 256 KiB, requires a current SHA-256 (or `ABSENT`) for write/delete, and requires exactly one matching fragment for replacement. Atomic replacement preserves existing permissions.
- Every successful edit saves a `DraftCheckpoint` with directory, base commit, input fingerprint and diff hash in signed run state. Resume verifies the checkpoint before invoking the model. Changes to requirements, upstream artifacts, feedback or candidate input create a fresh draft. Old drafts remain on disk for inspection.
- Git computes the final diff; model final text is not the patch. Existing scope checks, generated-test policy, proposal hashes, approval, application and downstream validation still apply.
- `DraftChecks` checks scope, generated-test policy and applicability. Where the next task is `VALIDATE` or `VALIDATE_RED`, it runs the corresponding trusted sandbox check. Intermediate patches whose tests depend on later patches defer the full suite to the downstream validation task.
- Each advance permits at most three drafting passes and three distinct nonempty draft checks. Rechecking the most recently checked diff reuses its result. Returning the same failed diff twice pauses without another sandbox run. Validation feedback is bounded and treated as untrusted data.
- Each drafting invocation permits at most 32 provider requests and 64 tool calls; chat/document invocations retain 8/12. All calls share the existing run ceiling and deadline rules. The application never increases the run ceiling automatically.
- `ClaudeResponses` discards every `max_tokens` response before executing tool calls or retaining history. At most one budgeted recovery request follows an empty final answer or token-limit response. Draft recovery may retain edit tools when budget permits; final-only requests cannot call tools.
- Supported adaptive model families use medium effort initially and low effort on recovery. Recovery disables thinking only for supported Sonnet 5 IDs, not Sonnet 5.5 or unknown model IDs. `max_tokens=32768` remains a hard ceiling; reasoning effort is not a guarantee of output. See the provider's [thinking](https://platform.claude.com/docs/en/build-with-claude/thinking) and [effort](https://platform.claude.com/docs/en/build-with-claude/effort) documentation.
- `GenerationUnavailableException` pauses recoverably with saved progress. Diagnostics remain available on Resume even without a failed validation attempt. Explicit revision clears their active association while retaining historical evidence.

## Consequences

Large features can accumulate many small edits without fitting the entire patch into a single model response. Real ADK tool-loop tests against a fake provider cover truncation and recovery without paid calls. Workspace tests cover isolation, optimistic concurrency, permission preservation and tampering; engine tests cover repair feedback, approval, no-progress and durable resume.

This does not guarantee eventual success. Infrastructure errors, policy violations, invalid checkpoints and exhausted budgets still stop work. A crash between a filesystem edit and checkpoint persistence fails closed on Resume; the filesystem and database are not a single transaction. Drafts consume disk and are retained rather than automatically deleted. A fresh ADK session resumes from files and diagnostics, not from persisted private thinking. Sandbox checks can be expensive, so repeat checks and passes are capped.

## Revisit when

ADK offers durable orchestration that preserves the factory's leases, audit, request reservation and approval boundaries, or draft storage needs transactional snapshots and retention management.
