# ADR 0010: Require the operator token for chat commands that change a run

**Status:** accepted

**In one paragraph.** The chat UI can run slash commands. The ones that create, advance or decide a run are accepted only when the HTTP request carries the operator token. Read-only commands and plain questions need no token. In practice, decisions are made on the operator page, which sends the token; the stock ADK chat UI does not.

## Context

The factory embeds Google ADK's web server, which exposes `/run`, `/run_sse` and `/run_live`. A chat message such as `/approve <hash>` is routed to the same service as the operator API. The operator API already required a token for every mutation. Without the same rule on chat, the token could be bypassed by posting a chat message instead.

The token is a random UUID created at startup and returned by `GET /factory/api/config`. Its purpose is to prove that a request comes from a page of this origin, which is the only kind of page that can read that response. It is a cross-site request defence, not a login.

## Decision

`LocalOperatorFilter`, inside the Spring Security chain:

- allows only loopback host names and same-origin requests;
- for `/factory/api/**`: any method other than GET, HEAD or OPTIONS needs `X-Factory-Token`;
- for `/run` and `/run_sse`: the body (buffered by `RequestBodyLimit`, at most 64 KiB) is parsed exactly as the agent will parse it. If the message is `/feature`, `/demo`, `/advance`, `/approve`, `/reject`, `/answer` or `/changes`, the token is required. An unreadable body is treated as state-changing;
- for `/run_live`: the token is always required, because messages on a live socket cannot be inspected by a filter.

The list of state-changing commands is owned by `OperatorCommands.changesWorkflowState`, next to the commands themselves.

## Consequences

- There is one rule for every surface: no token, no change.
- Typing `/approve ...` into the ADK chat UI is refused with a message pointing to the operator page. `/help`, `/tools`, `/runs`, `/select`, `/status`, `/review` and plain questions still work there.
- Scripts can act through chat by sending the header (`scripts/checks/web_smoke.py` does this for the API).
- Any process on the same machine can fetch the token. The control is against other web origins, not against local users.

## Revisit when

- The factory is reachable by anyone other than its single local operator: replace the token with real authentication and roles before doing so.
