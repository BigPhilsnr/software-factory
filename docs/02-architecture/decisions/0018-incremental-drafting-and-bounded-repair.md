# ADR 0018: Incremental drafting and bounded repair

**Status:** accepted; partially supersedes ADR 0015's text-only PATCH generation.

**Update:** [ADR 0019](0019-model-call-counts-are-metrics.md) supersedes the model-call limits described below; historical decisions are retained for traceability.

**In one paragraph.** Live PATCH agents edit a separate, durable draft in small steps. Git produces the proposal from those edits. Bounded validation feedback lets the agent repair ordinary implementation errors before review, while the existing exact-hash approval and candidate validation gates remain authoritative.

## Context

A single response containing a large unified diff can exhaust the provider output limit, including reasoning tokens, without producing a usable artifact. Retrying the same large response discards progress. Provider text saying “completed” also cannot establish that a feature was applied or validated.

## Decision

- `PatchDrafting` snapshots the current candidate into an independent Git repository under `.runs/drafts/`. The agent never writes directly to the approval candidate. Fixture generation remains deterministic.
- ADK `FunctionTool` exposes `read_draft_file`, `write_draft_file`, `replace_draft_text`, `delete_draft_file` and `check_draft` only to live PATCH workers. Chat and document workers retain read-only tools. There is no arbitrary shell tool.
- `DraftFiles` confines edits to task scope, rejects traversal, symlinks and credential paths, limits UTF-8 files to 256 KiB, requires a current SHA-256 (or `ABSENT`) for write/delete, and requires exactly one matching fragment for replacement. Atomic replacement preserves existing permissions.
- Every successful edit saves a `DraftCheckpoint` with directory, base commit, input fingerprint and diff hash in signed run state. Resume verifies the checkpoint before invoking the model. Changes to requirements, upstream artifacts or candidate input create a fresh draft. Review feedback for a pending unapproved proposal may retain the draft only when the specification, upstream artifact hashes and candidate bytes are unchanged; the checkpoint is verified again and the agent must process the new feedback before a fresh approval. Old drafts remain on disk for inspection.
- A resumed nonempty draft with a direct green/red validation task is checked before another model call. Missing final model text or an exhausted draft-tool batch hands saved files to that validator. A passing check proceeds to the existing approval gate; failed checks feed a fresh bounded repair pass. Empty drafts and patches without a substantive validation task still pause on unavailable generation.
- Git computes the final diff; model final text is not the patch. Existing scope checks, generated-test policy, proposal hashes, approval, application and downstream validation still apply.
- `DraftChecks` checks scope, generated-test policy and applicability. Where the next task is `VALIDATE` or `VALIDATE_RED`, it runs the corresponding trusted sandbox check. Intermediate patches whose tests depend on later patches defer the full suite to the downstream validation task.
- Each advance permits at most three drafting passes and three distinct nonempty draft checks. Rechecking the most recently checked diff reuses its result. Returning the same failed diff twice pauses without another sandbox run. Validation feedback is bounded and treated as untrusted data.
- The third failed check stops the invocation immediately. `ToolSession` retains generation-stop exceptions and refuses later edits or provider requests instead of converting the stop into a recoverable tool error. The pause diagnostic includes the latest compiler/test failure; successful third checks still proceed to review.
- All engineering prompts include the exact bundled validation POM and supported database/test setup. Empty baselines no longer leave the agent to guess dependencies or Spring Boot test APIs. Candidate build files do not override the trusted build.
- Test-policy violations in unapproved draft content are bounded repair feedback; scope and integrity violations remain policy stops. Historical integration-tag stops have a separate audited recovery transition, exposed as **Recover saved draft**, which verifies original inputs and checkpoint integrity before reopening repair. It never approves or applies the draft.
- Each drafting invocation permits at most 32 provider requests and 64 tool calls; chat/document invocations retain 8/12. All calls share the existing run ceiling and deadline rules. The application never increases the run ceiling automatically.
- `ClaudeResponses` discards every `max_tokens` response before executing tool calls or retaining history. At most one budgeted recovery request follows an empty final answer or token-limit response. Draft recovery may retain edit tools when budget permits; final-only requests cannot call tools.
- Supported adaptive model families use medium effort initially and low effort on recovery. Recovery disables thinking only for supported Sonnet 5 IDs, not Sonnet 5.5 or unknown model IDs. `max_tokens=32768` remains a hard ceiling; reasoning effort is not a guarantee of output. See the provider's [thinking](https://platform.claude.com/docs/en/build-with-claude/thinking) and [effort](https://platform.claude.com/docs/en/build-with-claude/effort) documentation.
- `GenerationUnavailableException` pauses recoverably with saved progress. Diagnostics remain available on Resume even without a failed validation attempt. Explicit revision clears their active association while retaining historical evidence.
- Long pause diagnostics retain the opening failure summary and final compiler/test errors, omitting intermediate startup logs so Resume receives the actionable failure.

## Consequences

Large features can accumulate many small edits without fitting the entire patch into a single model response. Real ADK tool-loop tests against a fake provider cover truncation and recovery without paid calls. Workspace tests cover isolation, optimistic concurrency, permission preservation and tampering; engine tests cover repair feedback, approval, no-progress and durable resume.

This does not guarantee eventual success. Infrastructure errors, policy violations, invalid checkpoints and exhausted budgets still stop work. A crash between a filesystem edit and checkpoint persistence fails closed on Resume; the filesystem and database are not a single transaction. Drafts consume disk and are retained rather than automatically deleted. A fresh ADK session resumes from files and diagnostics, not from persisted private thinking. Sandbox checks can be expensive, so repeat checks and passes are capped.

## Revisit when

ADK offers durable orchestration that preserves the factory's leases, audit, request reservation and approval boundaries, or draft storage needs transactional snapshots and retention management.
