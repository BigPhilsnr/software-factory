# Glossary

**In one paragraph.** These are the words the code, the operator page and the rest of the documentation use. Each entry gives a plain definition and the class where the concept lives, so you can go from a word to the code.

## Core terms

| Term | Meaning | In the code |
| --- | --- | --- |
| **Scenario** | A requirement, a baseline Git tag, and the list of tasks needed to meet the requirement. | `scenario/ScenarioSpec`, `scenarios/*/scenario.json` |
| **Task** | One unit of work in a scenario. It has an id, a kind, a stage, the tasks it depends on, and for patches the paths it may write. | `scenario/TaskSpec` |
| **Task kind** | What a task does: `ARTIFACT` (write a document), `PATCH` (propose a diff), `VALIDATE` (the full test suite must pass), `VALIDATE_RED` (one new test must fail), `CLARIFY` (ask the operator), `RELEASE` (final approval). | `scenario/TaskKind` |
| **Task graph** | The tasks and their dependencies. It is checked before a run starts: no cycles, no dependency on a later stage, every patch covered by a validation, and the release downstream of everything. | `scenario/TaskGraph` |
| **Run** | One execution of a scenario. It has an id, a status, a status per task, and counters such as model calls used. | `run/RunState` |
| **Run status** | `CREATED`, `RUNNING`, `PAUSED`, or one of four final states: `COMPLETED`, `FAILED`, `SAFE_STOPPED`, `NOT_APPROVED`. | `run/RunStatus` |
| **Mode** | `fixture` replays recorded output. `live` calls the model. | `run/RunMode` |
| **Baseline** | The Git tag a run starts from, resolved to a commit when the run is created so it cannot drift. | `RunState.baselineCommit` |
| **Candidate** | The run's private copy of the code: a detached Git worktree at `.runs/<run-id>/`. Patches are applied here and nowhere else. | `candidate/GitWorkspace` |
| **Agent** | A model invocation that produces the output of one task. It can call read-only tools. It returns text only. | `generation/AgentRuntime` |
| **Artifact** | The text output of a task, for example a plan or a test report. | `run/ArtifactTask` |
| **Patch** | A unified diff proposed by an agent. It is inert text until the factory checks and applies it. | `run/PatchTask` |
| **Write scope** | The path prefixes a patch task is allowed to change. A patch that touches anything else stops the run. | `governance/PatchScope` |
| **Evidence** | An immutable file holding a task output, a reviewed proposal or a failure diagnostic: `evidence/<run-id>/<task>-v<N>.txt`. Once written it can never be replaced with different content. | `audit/EvidenceStore`, `run/RunEvidence` |
| **Approval gate** | A point where the run pauses until the operator approves. The approval names a hash, and is valid only for exactly that content. | `run/ApprovalGate`, `run/ApproveStep` |
| **Approval hash** | For a patch: SHA-256 over the patch, the baseline commit, the requirement hash, the scenario hash and the hashes of all completed outputs. For a release: SHA-256 over the full candidate diff and those output hashes. | `ApprovalGate.patchHash`, `ApprovalGate.releaseHash` |
| **Validation** | Running the candidate's Maven tests in the sandbox and reading the result from the Surefire XML reports. | `validation/SandboxValidator` |
| **Sandbox** | A Docker container with no network, a read-only filesystem, no Linux capabilities and CPU, memory and process limits. | `validation/DockerSandbox` |
| **Release** | The last task of a run. The candidate must still equal what validation tested, and the operator approves the hash of its complete diff. It marks the run complete. It does not merge or deploy. | `run/ReleaseTask` |
| **Lease** | Exclusive permission for one process to change one run, held as a PostgreSQL advisory lock for the length of one transition. A second caller gets a conflict instead of waiting. | `audit/RunLeases`, `audit/RunLease` |
| **Revision (number)** | A counter on the stored run. A writer must present the revision it loaded; a stale writer is refused. | `audit/RunJournal` |
| **Audit event** | One row saying what changed in a run and why, stored in the same transaction as the new run state. | `audit/EventTypes`, `audit/RunJournal` |
| **Audit chain** | The audit events of a run linked by hashes: each event's hash covers the previous event's hash, so editing or removing an old event breaks every later one. New events use HMAC-SHA256 with a key kept outside the database. | `audit/AuditChain`, `audit/AuditTrail` |
| **Audit key** | The secret for the audit chain HMAC: `FACTORY_AUDIT_KEY`, or a development key in `.runs/audit.key` when that is not set. | `audit/AuditKey` |

## Things that happen to a run

| Term | Meaning | In the code |
| --- | --- | --- |
| **Advance** | Execute ready tasks until the run finishes or must wait. | `run/AdvanceRun` |
| **Clarify** | Record the operator's answer to a `CLARIFY` task. | `run/AnswerClarification` |
| **Revise** | The operator sends a task back, optionally with feedback. That task and everything downstream of it become stale and are generated again; their patches are removed from the candidate. | `run/ReviseRun` |
| **Stale** | A task whose output was invalidated by a revision and has not been queued again yet. | `run/TaskStatus.STALE` |
| **Safe stop** | The run ends as `SAFE_STOPPED` because a rule was broken: a patch outside its scope, a tampered record, an exhausted model budget. Never retried. | `run/FailureHandling` |
| **Retry** | A task that fails for an ordinary reason may run once more after the operator resumes. A second failure ends the run as `FAILED`. | `FailureHandling.MAX_ATTEMPTS` |
| **Revision required** | A validation failed for a reproducible reason (tests fail, code does not compile). Running it again cannot help, so the run pauses until an upstream task is revised. | `RunState.revisionRequiredTask` |
| **Recovery** | On the next advance after a crash, tasks left `RUNNING` adopt output they had already saved or are queued again. An interrupted patch also resets the candidate. | `run/RecoverInterruptedRun` |

## People and surfaces

| Term | Meaning |
| --- | --- |
| **Operator** | The human who drives and approves runs. Identified only by a label (`FACTORY_OPERATOR`, default the OS user name) written into audit events. |
| **Operator page** | The browser UI at `/factory/`. |
| **Operator token** | A random value created when the factory starts and handed to the operator page. Every request that changes a run must carry it in `X-Factory-Token`. It is not a login. |
| **Chat** | The ADK developer UI at `/dev-ui/`. Plain text gets a read-only answer; literal slash commands act on runs. |
| **Control plane** | Another name for the factory: the part that controls work rather than doing it. |
| **Control DB** | The factory's PostgreSQL database. |

## Shortener terms

| Term | Meaning | In the code |
| --- | --- | --- |
| **Link** | An immutable pair of short code and target URL. | `link/Link` |
| **Code** | 4 to 32 characters from `a-z`, `0-9` and `-`, stored lowercase and matched case-insensitively. Generated codes are 8 random characters. | `link/LinkCodes`, `shorten/CodeGenerator` |
| **Alias** | A code chosen by the client instead of generated. | `shorten/ShortenLink` |
| **Visit** | One `GET /{code}` redirect. `HEAD` is a preview and is not counted. | `redirect/ResolveLink` |
| **Coalescing** | Adding visits up in memory and writing one update per link per flush, instead of one database write per visit. | `analytics/CoalescingVisitRecorder` |
