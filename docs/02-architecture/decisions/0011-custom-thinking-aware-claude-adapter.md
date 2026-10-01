# ADR 0011: A custom Claude adapter instead of ADK's built-in model class

**Status:** accepted

**In one paragraph.** Live generation goes through Google ADK, but the model class is our own `ThinkingAwareClaude`, a subclass of ADK's `Claude`. It keeps the provider's complete tool-use turns so they can be replayed correctly, hides thinking blocks from everything outside the provider round trip, and enforces the factory's request and tool budgets on every call.

## Context

ADK provides the agent loop, tool declaration and session handling. Its Claude adapter did not meet four needs:

1. **Round trips with thinking.** When a response contains signed thinking blocks and a tool call, the next request must send that assistant turn back unchanged. ADK stores only the public function call.
2. **Privacy of thinking.** Thinking text must not appear in ADK events, artifacts or evidence.
3. **Budgets before the call.** Every provider request must first reserve budget in the run (or the chat ledger) and be audited. That hook has to sit exactly where the HTTP call is made.
4. **A guaranteed final answer.** The last allowed request of an invocation must produce the artifact, not another tool call.

## Decision

`ThinkingAwareClaude.generateContent`:

- calls `ToolSession.reserveRequest()` before contacting the provider; a refused reservation aborts the request;
- stores the original provider message for each tool call id and replays it verbatim when ADK sends the function call back;
- returns only text and tool-call parts; thinking and redacted-thinking blocks are dropped from the visible response;
- allows one tool call at a time (`disableParallelToolUse`), and on the last budgeted request sets `tool_choice: none`, appends a "final response" instruction and records `TOOL_BUDGET_FINALIZING`;
- treats a tool call received after tools were disabled as a policy violation;
- sets `max_tokens` to 32,768 and fails if the response stops for length, so a truncated patch is never used;
- supports non-streaming calls only.

`ModelClients` builds the Anthropic client with zero SDK retries and a 5-minute request timeout, so no paid call happens outside the budget.

## Consequences

- The factory, not the SDK, decides when a call may be made and how often it is retried.
- The adapter is coupled to ADK 1.10.1 and Anthropic SDK 2.15.0. The SDK version is pinned in the POM to the one ADK was built against. An ADK upgrade means re-testing this class.
- `ThinkingAwareClaudeTest` and `AgentToolLoopTest` exercise the protocol through a real ADK runner against a local fake HTTP provider, with no key.
- The adapter does not itself turn extended thinking on; it only handles thinking blocks when the provider returns them.

## Revisit when

- ADK's own adapter preserves signed thinking across tool calls and offers a pre-request hook: delete this class.
