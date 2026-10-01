# Agent tools

The native [ADK chat](http://localhost:8000/dev-ui/?app=software_factory) and live factory task agents share seven read-only tools. Ask naturally; the model chooses the tool and uses its result in its answer. Send `/tools` for the catalog. The existing `.env` `ANTHROPIC_API_KEY` and `CLAUDE_MODEL` are sufficient; no additional search key is needed.

| Tool | Example request |
| --- | --- |
| `search_web` | “Search official Google ADK Java documentation for function tools and cite the sources.” |
| `fetch_page` | “Read https://example.com and summarize the page.” |
| `list_files` | “Show the source files for the factory’s tool system.” |
| `read_file` | “Read RunEngine and explain how approvals are enforced.” |
| `search_repository` | “Find references to MAX_MODEL_REQUESTS.” |
| `inspect_git` | “Summarize the current source diff and recent commits.” |
| `current_time` | “What is the current UTC time?” |

Chat reads the current checkout. A task worker reads its isolated candidate checkout, so its observations match the files it is changing. A selected run does not switch chat's filesystem context. Git inspection supports fixed `status`, `diff` and `log` operations against allowlisted paths. It does not accept shell commands.

## Boundaries and cost

Each answer or generated task allows at most **8 provider requests and 12 tool calls**. All worker requests, including nested web searches and model continuations after tool results, reserve a call against the durable run budget before contacting the provider. Chat has the per-answer limits but is billed separately from workflow budgets. There is no automatic provider retry.

The last task request is reserved for the final answer or patch: the adapter disables tool use on that request and records `TOOL_BUDGET_FINALIZING`. Nested web search cannot consume that reserved slot. The separate run-wide ceiling remains enforced; finishing an artifact is not a guarantee that its code passes validation. A historical run that already reached `SAFE_STOPPED` remains terminal and must be inspected before starting a new request.

Search uses Anthropic's [basic web search tool](https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool), `web_search_20250305`, with at most two searches per invocation and no code execution. This incurs search charges as well as token charges. The provider account/model must support and enable web search. An unavailable search is returned as an error; the agent must not claim it searched successfully.

Page reading accepts public HTTPS on port 443, checks the actual DNS addresses used to connect, rechecks up to three redirects, and blocks private, loopback, metadata, reserved and local addresses. Requests use no proxy, cookies, credentials or JavaScript. Each HTTP request has a 15-second timeout; pages are limited to 512 KiB and 14,000 characters of extracted HTML/plain text/JSON. This is text browsing, not a graphical browser: it cannot log in, click buttons, take screenshots or inspect JavaScript-rendered pages.

Repository tools allow source and documentation paths only. They deny environment files, credential paths, symlinks and paths outside the checkout. Reads are capped at 200 lines and 256 KiB per file; search uses literal text, up to 80 matches. Tool outputs are capped at 16,000 characters. Source/web content is treated as untrusted data. These are application access controls, not an OS sandbox; do not place secrets in source/documentation files that are intended to be read by the model. Public search queries and fetched URLs leave the machine.

Code generation, patch application, sandbox tests, changes to the task graph, and approvals remain with the existing control plane. Use `/feature`, `/advance` and `/review` for those actions. The model has no unrestricted host shell, filesystem-write, approval, database or deployment tool.

## Traceability and tests

Chat answers include an actual tool activity footer, such as `read_file OK, search_web ERROR`. Nested tool events are not forwarded into native ADK's Events panel; the footer reports their outcomes. Workflow tools record durable `TOOL_STARTED` and `TOOL_FINISHED` audit events with task/tool names, outcome, elapsed time and input/output hashes. Raw arguments, source contents, provider credentials and private thinking are not placed in those audit fields. The operator page shows these events in its activity list.

`ThinkingAwareClaude` translates ADK function declarations, calls and results. It retains signed provider thinking only in memory to replay it correctly on a continuation, while excluding that thinking from public ADK events and artifacts. Tests exercise this protocol through a real ADK runner and local fake HTTP provider, without a paid key.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q -pl factory -am test
# With the local UI running: this explicitly makes paid live calls.
python3 scripts/checks/live_tools_smoke.py --live
```

The live smoke check exercises all seven tools through `/run_sse`, checks their reported outcomes and that no new live workflow was created, and saves a result under `.runs/tool-checks/`. Run it without another operator creating live workflows. Unit tests cover credential/path denial, symlinks, private URLs and addresses, HTML extraction, read-only Git inspection, request budgets, safe errors, audit hashes and provider tool round trips.
