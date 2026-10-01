# ADR 0015: Agents propose inert text; deterministic code decides and acts

**Status:** accepted

**In one paragraph.** A model never performs an action. It returns a document or a unified diff as text. Java code that does not depend on model output checks the diff, applies it, runs the tests, counts the budget and records the result. Authorisation never comes from something a model wrote.

## Context

The original plan preferred a coding-agent worker that edits files itself. That path was not verified in this environment, and it would have put a model in direct control of a working copy. Model output is also the main place where prompt injection from web pages or repository text can surface.

## Decision

- `AgentRuntime.generate(role, prompt)` returns a `String`. That is the entire interface between agents and the engine. Fixture mode (`FixtureRuntime`) and live mode (`AdkClaudeRuntime`) implement the same interface.
- Agents have seven read-only tools. There is no tool that writes a file, runs a command, queries a database, approves or deploys.
- A diff becomes a change only through `PatchScope`, `GeneratedTestPolicy`, `PatchPolicy`, `git apply --check` and, where required, an operator approval.
- The task graph comes from trusted templates. Requirement text is data inside a fixed workflow.
- Retries are a control-plane decision (one retry, recorded), not an SDK feature.
- Repository text, tool results and web content are wrapped in random delimiters and labelled as data. This helps the model; it is not relied on as a security control.

## Consequences

- A prompt injection can make an agent write a bad document or a bad diff. It cannot make the factory skip a check.
- Asking for a whole patch as text is harder for a model than editing files step by step. Large live patches have failed for this reason (malformed hunks, output limits). The feature workflow therefore splits production and test changes into two smaller patch tasks.
- Fixture replays prove the control plane, not the model.

## Revisit when

- A sandboxed coding worker is available whose file edits can be captured as a diff and passed through the same checks.
