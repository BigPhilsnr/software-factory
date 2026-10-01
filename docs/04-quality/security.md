# Security model

**In one paragraph.** The factory assumes one trusted operator on one trusted machine and treats three things as hostile: what the model writes, what the web returns, and what candidate code does when it runs. Controls sit at each point where those inputs enter: path and policy rules for patches, an offline container for execution, hash-bound approvals for human decisions, and a keyed hash chain for the record. This page lists each asset, the threat, the control and, plainly, what is still exposed.

The boundary diagram is in [factory architecture](../02-architecture/factory.md#trust-boundaries).

## Assumptions

| Assumed trusted | Consequence if the assumption is false |
| --- | --- |
| The operator | A careless approval lets a bad change through. Nothing can prevent that. |
| The host, its OS users and the Docker daemon | Anyone with local access can fetch the operator token, read `.env` and the audit key, and edit the databases. |
| The control database's contents, up to what the audit chain can detect | See the audit record rows below. |
| The local Maven repository (`~/.m2`) | A poisoned artifact there would run inside the sandbox during validation. |
| The model provider | Prompts include requirement text and shortener source by design. |

| Treated as untrusted | Because |
| --- | --- |
| Model output: documents, diffs, tool requests | Models make mistakes and can be steered by injected text. |
| Web content: search results, fetched pages | Anyone can publish text that looks like instructions. |
| Candidate code and its tests | They were written by a model and are executed. |
| Repository and tool text placed in prompts | It may contain injected instructions; it is passed as delimited data. |
| Browser requests from other origins | Any web page can make a browser send requests to `localhost`. |

## Threat model: factory

| Asset | Threat | Control | Residual risk |
| --- | --- | --- | --- |
| Host and network | Candidate code or tests behave maliciously while being validated | Docker container with no network, read-only root, all capabilities dropped, no new privileges, CPU, memory and process limits, the host user's uid and gid instead of root, 10-minute limit, no credentials or environment passed in (`DockerSandbox`) | A container is not a complete security boundary. The Docker daemon is trusted. |
| Test verdict | Candidate build logic reports success without running tests | Maven is driven by the factory's own POM; the candidate's `pom.xml` is ignored. Reports are read from a fresh host-owned folder, must be regular files written during this run, must show at least one test and no failure, error or skip, and every changed test class must have executed (`SandboxValidator`, `SurefireReports`) | **Sandboxed tests can forge their own Surefire report.** Test code runs inside the container, where the report folder is writable. The control is the operator reading the test patch and the release diff. |
| Test suite | A patch weakens the suite to get a green result | `GeneratedTestPolicy` refuses deleting a test class or tagging a test `integration`. Test patches need approval in the feature workflow. | Assertions can still be weakened inside a test body. Human review. |
| Candidate content | A patch writes outside its task, or hides a file from review | `PatchScope` (prefix allowlist, no symlinks, submodules, renames, `.git*`, `target/`, `.env*`). Applied paths must equal reviewed paths. The release diff is built with a forced add into a private index, so ignored files appear ([ADR 0008](../02-architecture/decisions/0008-forced-private-index-release-diff.md)) | A harmful change inside the allowed paths passes these checks. Human review. |
| Approval | The operator approves one thing and another is applied (swap after review, stale approval) | The approved hash covers the content, the baseline commit, the requirement, the scenario and all completed outputs. At approval the stored proposal is re-hashed. At release the diff must equal the validated diff and the live candidate (`ApprovalGate`, `ApproveStep`, `ReleaseTask`) | The operator can approve without reading. |
| Operator authority | A web page in the operator's browser sends actions to `localhost:8000` | Server bound to `127.0.0.1`; loopback host names only; same-origin check; `X-Factory-Token` on every mutation, on run-changing chat commands and on live chat sessions; JSON bodies only; strict Content-Security-Policy and frame denial on the operator page (`LocalOperatorFilter`, `FactoryWebConfiguration`) | The token is not authentication. Any local process can read it from `/factory/api/config`. The operator name in audit events is an unverified label. Requests with no `Origin` header pass the origin check. |
| Operator authority | Model output or injected text triggers an action | Agents have no tool that writes, executes or approves. Chat commands are matched from the literal user message, never from model text. Plain-language "yes" or "approve" does nothing (`OperatorCommands`) | Injected text can still make an agent produce a misleading document or a bad diff, which the operator then has to catch. |
| Audit record | Someone with database write access rewrites history | HMAC-SHA256 chain keyed outside the database; the state snapshot is part of each hash; verification before every advance, approval and release; no downgrade to the keyless scheme; revision check on every write ([ADR 0007](../02-architecture/decisions/0007-hmac-audit-chain-with-versioned-schemes.md)) | **Legacy-scheme audit rows:** a chain containing only scheme-1 rows can be recomputed by a database writer. Removing the newest events and restoring the earlier snapshot is not detected. Whoever holds both the key and the database can rewrite everything. There is no external anchor and no key rotation. |
| Evidence files | Outputs are changed on disk after the fact | Create-without-replace writes; hashes of completed outputs are kept in the run state and checked before every advance, approval and release; symlinks refused (`EvidenceStore`, `EvidenceIntegrity`) | Proposal copies (`-proposal-v`) and diagnostics (`-error-v`) are stored but not hash-checked. A local user can delete evidence; the run then safe-stops or shows the artifact as unavailable. |
| Secrets (provider key, database passwords, audit key) | Leak into prompts, logs, the sandbox or the browser | Tools and prompt context read only allowlisted paths and extensions and refuse dot-paths such as `.env`; the sandbox receives no environment; settings redact credentials when printed; tool errors expose only an exception type; the config endpoint returns flags, not keys; the shortener launcher drops model variables | A secret committed to an allowlisted source or documentation file is readable by the model. There is no secret scanner. `.env` and the development audit key are plain files. |
| Private data | An agent sends data out through its tools | Fetches are limited to URLs a search returned, with query and fragment stripped, same-origin redirects only, public HTTPS addresses only (`WebAccessPolicy`, `PublicUrls`, `PublicAddresses`) | **Search queries are an outbound channel.** A query of up to 500 characters goes to the provider and may contain anything the agent has read. Requirement text and shortener source are sent to the provider in prompts by design. |
| Internal network | An agent fetches an internal address (server-side request forgery) | The address check runs on the addresses actually used to connect, for names and IP literals, and again after each redirect | None known beyond the trust in the check's range lists. |
| Money | Unbounded paid calls | Per-run request ceiling fixed at creation; 8 requests and 12 tool calls per invocation; deadlines; zero SDK retries; persistent daily chat budget; at most two runs advancing | Budgets count requests, not tokens or currency. One request may return up to 32,768 output tokens. |
| Availability | Oversized input or runaway processes exhaust the host | 64 KiB request bodies; every host command has a timeout and an output cap; sandbox resource limits; orphaned containers swept at start and stop | Worktrees and evidence grow until pruned. |
| Build supply chain | A changed image, action or dependency alters the build | Sandbox image pinned by digest and cross-checked in CI; GitHub Actions pinned by commit; enforcer upper-bound rule; FindSecBugs | No dependency vulnerability scan and no SBOM. |

## Threat model: shortener

| Asset | Threat | Control | Residual risk |
| --- | --- | --- | --- |
| Visitors | A short link points at an internal or deceptive target | `UrlPolicy`: public `http` or `https` only, no credentials, no local names, no private or reserved IP literals, no ambiguous numeric hosts, not the shortener itself | A DNS name that resolves to a private address is accepted (no DNS lookup). No reputation or phishing check. Links cannot be removed. |
| Creation capacity | One client floods link creation | Fixed-window limit per client network; bounded tracking table; only valid requests consume quota | Per process. Behind a proxy the limit applies to the proxy unless forwarded headers are trusted, and trusting them wrongly lets clients spoof addresses. Up to twice the limit across a window boundary. |
| Redirect availability | Analytics or database trouble takes redirects down | Cache for immutable links; separate analytics pool; non-blocking visit recording; fail-fast timeouts | Uncached codes need the database. |
| Database | Injection or resource exhaustion | Parameterised SQL only; 64 KB body limit; statement, lock and socket timeouts; unique and check constraints | |
| Management surface | Internal details exposed through Actuator | Only health and info are permitted; health details are never shown; everything else under `/actuator` is denied | |
| Counts | Inflated or lost visit counts | `HEAD` is not counted; drops are metered | Automated `GET` requests are counted. Counts are best effort. |
| Transport | Eavesdropping or tampering | None in the application | The shortener serves plain HTTP on all interfaces and has no authentication. Put TLS and any access control in front of it. |

## Known residual risks, in one place

1. Sandboxed tests can forge their own Surefire report.
2. Audit chains made only of legacy-scheme rows can be rewritten by a database writer; truncating the newest events is not detected; there is no external anchor.
3. Search queries are an outbound channel to the model provider.
4. The operator token defends against other web origins, not against local processes or users. There is no authentication.
5. Human review is the only control for a harmful change inside the allowed paths.
6. Secrets committed to allowlisted files are readable by the model.
7. Budgets limit request counts, not cost.
8. Local database passwords default to demonstration values.

## Before any non-local use

- Put real authentication and roles in front of the operator API and remove reliance on the token.
- Set `FACTORY_AUDIT_KEY` from a secret store, and anchor chain heads somewhere the database owner cannot write.
- Change both database passwords and restrict network access to the databases.
- Add an independent acceptance check that runs outside the sandbox against the built candidate.
- Terminate TLS in front of both applications.
- Add dependency and secret scanning to CI.
