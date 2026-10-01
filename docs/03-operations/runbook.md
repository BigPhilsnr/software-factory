# Runbook

**In one paragraph.** Everything runs on one machine: two PostgreSQL containers, the shortener on port 8080 and the factory on port 8000 (loopback only). Python launchers load `.env`, pick a JDK and start each application. This page lists how to start and stop things, every setting, where to look for health, metrics and logs, how to prune old runs, and what to do for each symptom you are likely to see.

## Prerequisites

| Tool | Version | Used for |
| --- | --- | --- |
| JDK | 21 or newer (21 to 25 for `mvn verify`) | Both applications |
| Maven | 3.9 or newer | Build, tests, launchers |
| Python | 3 | Launchers and check scripts |
| Git | any recent | Candidate worktrees; the factory must run inside this repository |
| Docker and `docker-compose` | running daemon | Databases and the validation sandbox |
| Node 22.12+ and Chrome | optional | `scripts/checks/browser_smoke.cjs` only |

## Ports

| Port | Bound to | Service | Change with |
| --- | --- | --- | --- |
| 8000 | `127.0.0.1` | Factory: operator page `/factory/`, chat `/dev-ui/`, API `/factory/api/` | Fixed in `factory/src/main/resources/application.properties` |
| 8080 | all interfaces | Shortener | `SHORTENER_PORT` |
| 5433 | `127.0.0.1` | Shortener PostgreSQL | `compose.yaml` and `SHORTENER_DB_URL` |
| 5434 | `127.0.0.1` | Control PostgreSQL | `compose.yaml` and `CONTROL_DB_URL` |

## Start

```sh
# Databases
docker-compose up -d shortener-db control-db

# First time, and after dependency changes: build and fill ~/.m2 for the offline sandbox
mvn -q test
docker pull maven@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320

# Shortener (foreground)
python3 scripts/shortener.py

# Factory web server (foreground, separate terminal)
python3 scripts/factory_web.py
```

Both launchers read `.env` if it exists, use `JAVA_HOME` if it points at JDK 21 or newer, and otherwise fall back to `/opt/homebrew/opt/openjdk@21`. `shortener.py` removes the model settings from the environment before starting the product.

When the factory is ready it logs one line with both URLs:

```text
Software Factory ready. Operator UI: http://localhost:8000/factory/  ADK chat: http://localhost:8000/dev-ui/?app=software_factory
```

Watch for these lines just before it:

| Log line | Meaning |
| --- | --- |
| `Sandbox validation unavailable: ...` | Docker, the image or the Maven cache is missing. Fixture and live runs will pause at validation. |
| `Live generation disabled: ...` | `ANTHROPIC_API_KEY` or `FACTORY_AUDIT_KEY` is not set. Fixture runs still work. |
| `FACTORY_AUDIT_KEY is not set; using the development audit key in .runs/audit.key` | Fine for fixtures. Set a real key before live runs. |

### Run the packaged jars instead

```sh
mvn -pl factory,shortener -am -DskipTests package
java -jar shortener/target/shortener-0.1.0-SNAPSHOT.jar
java -jar factory/target/factory-0.1.0-SNAPSHOT.jar      # run from the repository root
```

`java -jar` does not read `.env`; export the variables yourself. The factory locates scenarios, `.runs/` and `evidence/` relative to the working directory (or the `factory.workspace` property).

## Stop

- **Applications:** Ctrl-C. Both shut down gracefully. The factory stops accepting actions, waits up to 30 seconds for running workers, then interrupts them and removes its own validator containers. A run interrupted this way recovers on its next advance. The shortener finishes in-flight requests and flushes pending visit counts.
- **Databases:** `docker-compose down`. Add `-v` only to delete the data volumes.

## Settings

Put settings in `.env` (copy [`.env.example`](../../.env.example)) or export them in the shell. An exported variable wins over `.env`. The launchers accept only the names marked "yes" in the `.env` column and stop with `Invalid .env entry on line N` for anything else; export the others in the shell.

### Factory

Read once at startup by `platform/FactorySettings`. An out-of-range number stops the process with a message naming the variable.

| Variable | Default | Allowed | `.env` | Purpose |
| --- | --- | --- | --- | --- |
| `ANTHROPIC_API_KEY` | none | | yes | Provider key. Required for live runs and chat answers. Read by the Anthropic SDK; never stored or logged by the factory. |
| `FACTORY_AUDIT_KEY` | none | at least 32 characters | yes | Secret for the audit chain HMAC. Required for live runs. Keep it stable: changing it makes existing chains fail verification. |
| `CLAUDE_MODEL` | `claude-sonnet-4-5` | a model id your key can use | yes | Model for tasks, chat and web search. |
| `FACTORY_MAX_MODEL_CALLS` | `24` | 1 to 100 | yes | Provider requests allowed per run. Fixed when the run is created. |
| `FACTORY_CHAT_DAILY_REQUESTS` | `80` | 1 to 10000 | yes | Provider requests allowed for chat per UTC day, across restarts. |
| `FACTORY_RUN_DEADLINE_SECONDS` | `900` | 1 to 3600 | yes | Deadline for one task generation. |
| `FACTORY_CHAT_DEADLINE_SECONDS` | `180` | 1 to 3600 | yes | Deadline for one chat answer. |
| `FACTORY_OPERATOR` | OS user name | text | yes | Label written into approval, clarification and revision events. Not verified. |
| `FACTORY_MAVEN_REPOSITORY` | `~/.m2/repository` | directory | yes | Maven repository mounted read-only into the sandbox. |
| `CONTROL_DB_URL` | `jdbc:postgresql://localhost:5434/control` | JDBC URL | yes | Control database. |
| `CONTROL_DB_USER` | `control` | | yes | |
| `CONTROL_DB_PASSWORD` | `control` | | yes | Also read by `compose.yaml` when the volume is first created. |

### Shortener

Environment variables referenced by `application.yml`:

| Variable | Default | `.env` | Purpose |
| --- | --- | --- | --- |
| `SHORTENER_PORT` | `8080` | yes | HTTP port. |
| `SHORTENER_BASE_URL` | `http://localhost:8080` | yes | Prefix of returned `shortUrl` values. Its host is also refused as a link target. Must be an absolute `http` or `https` URL without query, fragment or credentials. |
| `SHORTENER_DB_URL` | `jdbc:postgresql://localhost:5433/shortener` | yes | Product database. |
| `SHORTENER_DB_USER` | `shortener` | yes | |
| `SHORTENER_DB_PASSWORD` | `shortener` | yes | Also read by `compose.yaml` when the volume is first created. |
| `SHORTENER_FORWARD_HEADERS_STRATEGY` | `none` | no (shell only) | Set to `native` or `framework` only behind a trusted reverse proxy that overwrites `X-Forwarded-For`. Otherwise clients can spoof their address and evade the rate limit. |

Tunables bound by `platform/ShortenerProperties` (prefix `shortener.`). They are set in `application.yml` and validated at startup; override them with the usual Spring Boot mechanisms.

| Property | Default | Meaning |
| --- | --- | --- |
| `rate-limit.requests-per-window` | `30` | Creations allowed per client per window |
| `rate-limit.window` | `60s` | Window length |
| `rate-limit.max-tracked-clients` | `10000` | Clients tracked exactly before aggregate buckets are used |
| `cache.max-links` | `10000` | Cached links |
| `cache.link-ttl` | `60s` | How long a link stays cached |
| `cache.max-misses` | `2000` | Cached "no such code" answers |
| `cache.miss-ttl` | `2s` | How long a miss stays cached |
| `analytics.flush-interval` | `1s` | How often pending counts are written |
| `analytics.max-pending-links` | `10000` | Links that may have unwritten counts |
| `analytics.flush-batch-size` | `500` | Rows per JDBC batch |
| `analytics.failure-log-interval` | `30s` | At most one flush-failure warning per interval |
| `analytics.pool-size` | `2` (1 to 16) | Connections reserved for analytics writes |
| `analytics.connection-timeout` | `1s` | Wait for an analytics connection |
| `analytics.query-timeout` | `2s` | Timeout of a flush statement |
| `http.max-request-body` | `64KB` | Largest accepted body on `/api/*` |
| `http.unavailable-retry-after` | `5s` | `Retry-After` value on `503` |

### Tests and check scripts (shell only)

| Variable | Default | Used by |
| --- | --- | --- |
| `JAVA_HOME` | | Launchers and Maven |
| `SHORTENER_TEST_DB_URL`, `SHORTENER_TEST_DB_USER`, `SHORTENER_TEST_DB_PASSWORD` | fall back to `SHORTENER_DB_*`, then the Compose defaults | Shortener integration tests |
| `CONTROL_DB_URL`, `CONTROL_DB_USER`, `CONTROL_DB_PASSWORD` | Compose defaults | Factory integration tests |
| `SHORTENER_BASE_URL` | `http://localhost:8080` | `scripts/checks/acceptance.py` |
| `FACTORY_TEST_URL` | `http://localhost:8000` | `web_smoke.py`, `browser_smoke.cjs` |
| `CHROME_PATH`, `FACTORY_BROWSER_MODULE` | macOS Chrome path; none | `browser_smoke.cjs` |

Maven does not read `.env`. Export database settings in the shell when they differ from the defaults.

### Database passwords

`compose.yaml` passes `SHORTENER_DB_PASSWORD` and `CONTROL_DB_PASSWORD` to PostgreSQL only when a volume is first created. Changing `.env` later does not change the stored role. Rotate with `ALTER ROLE ... PASSWORD ...` inside the container, or recreate the volume.

## Health

| Check | Command | Healthy answer |
| --- | --- | --- |
| Shortener ready (includes database) | `curl -s http://localhost:8080/actuator/health/readiness` | `{"status":"UP"}` |
| Shortener alive | `curl -s http://localhost:8080/actuator/health/liveness` | `{"status":"UP"}` |
| Factory ready (includes control database) | `curl -s http://localhost:8000/actuator/health/readiness` | `{"status":"UP"}` |
| Sandbox ready | `curl -s http://localhost:8000/factory/api/config` | `"validator":{"ready":true,...}` (re-checked at most every 30 s) |
| Product behaviour | `python3 scripts/checks/acceptance.py` | `PASS` lines, exit code 0 |
| Operator controls | `python3 scripts/checks/web_smoke.py` | exit code 0 (creates fixture runs) |

Health details are never shown over HTTP. Readiness says nothing about analytics lag or about another instance's cache.

## Metrics

**Factory.** Run metrics are computed from audit events, not from in-memory counters, so they survive restarts.

| Where | What |
| --- | --- |
| `GET /factory/api/metrics` | Outcome and reliability figures over the 100 most recently updated runs, fixture and live separately |
| `GET /factory/api/runs/{id}` and the operator page | Per-run elapsed time, retries, rollbacks, replans, recoveries, approvals |
| `python3 scripts/factory_cli.py metrics <run-id>` | The same per-run figures as JSON |

Definitions are in the [scorecard](../04-quality/scorecard.md#reliability-metrics).

**Shortener.** Micrometer meters are registered, but no metrics endpoint is reachable over HTTP: `PublicApiSecurity` denies every Actuator path except health and info. To read the meters, attach a Micrometer registry or relax that rule deliberately.

| Meter | Meaning |
| --- | --- |
| `shortener.analytics.recorded` | Visits accepted for counting |
| `shortener.analytics.flushed` | Visits written to the database |
| `shortener.analytics.dropped{reason=overflow}` | Visits dropped because too many links were pending |
| `shortener.analytics.dropped{reason=flush_failed}` | Visits dropped after a failed flush could not be merged back |
| `shortener.analytics.flush.failures` | Failed flush attempts |
| `shortener.analytics.pending` | Links with unwritten counts |
| `shortener.ratelimit.overflow` | Creations counted in a shared bucket because the client table was full |
| Caffeine cache meters for `shortener.links` and `shortener.link-misses` | Hits, misses, evictions, size |
| Hikari meters for the primary pool and `analytics-pool` | Connection usage and waits |

## Logs

| Application | Destination | Format | Levels |
| --- | --- | --- | --- |
| Factory web and CLI | stderr | `HH:mm:ss LEVEL logger [run=<id> task=<id>] - message` | `dev.softwarefactory` at INFO, everything else at WARN |
| Shortener | stdout | Spring Boot default with `[<request id>]` after the level | Spring Boot defaults |

The CLI prints its result as JSON on stdout and diagnostics on stderr, so its output can be piped to a JSON parser.

Useful factory lines: `Created ... run`, `Advancing run`, `task ... failed (attempt n/2)`, `paused at task ...: infrastructure unavailable`, `safe-stopped at task ...`, `approved by ...`, `requires revision`, `Run ... completed`. The complete history of a run is its audit trail, not the log: see the Activity list on the operator page.

Secrets are not logged. `FactorySettings.toString()` omits credentials and the audit key, and tool failures are reported by exception type only.

## Prune old runs

Worktrees under `.runs/` and files under `evidence/` are kept until you remove them.

```sh
python3 scripts/factory_cli.py prune                 # list finished runs older than 30 days
python3 scripts/factory_cli.py prune --days 7        # choose another age
python3 scripts/factory_cli.py prune --days 7 --apply  # delete their worktree and evidence
```

Only runs in a final state whose `finishedAt` is older than the cut-off are touched. Database rows and audit events are always kept, so `verify-audit` still works; the artifacts of a pruned run can no longer be displayed.

Validator containers are named `factory-validator-<uuid>`. Leftovers from a crashed process are removed when a factory starts. To remove them by hand: `docker ps -a --filter name=factory-validator- -q | xargs docker rm -f`.

## Roll back

- **Code:** check out the reviewed Git tag or commit and restart.
- **Schema:** migrations are forward-only and so far additive. Rolling code back does not undo a migration, and a Git reset never restores data. Try a migration on a disposable database before applying it to one you care about.
- **A run:** runs are never rolled back. Use **Request changes** to redo part of a run, or reject it and start another.

## Troubleshooting

### Factory

| Symptom | Likely cause | What to do |
| --- | --- | --- |
| Start / resume answers `503` with "Docker is not available" | Docker daemon is not running | Start Docker Desktop or Colima, then resume. |
| `503` with "Validator image missing; run: docker pull maven@sha256:..." | Sandbox image not pulled | Run the printed command. |
| `503` with "Maven cache missing: ..." | `FACTORY_MAVEN_REPOSITORY` does not exist | Run `mvn -q test` once, or point the variable at your repository. |
| Run pauses with event `INFRASTRUCTURE_UNAVAILABLE` and "Offline Maven cache is missing required artifacts" | The sandbox is offline and a dependency is not in `~/.m2` | Run `mvn -q test` with network access, then resume. No retry is used up. |
| Run pauses with event `RETRY_AVAILABLE` | A task failed once (for example a malformed patch) | Read the diagnostic shown on the page, then resume. A second failure ends the run as `FAILED`. |
| Run pauses with event `REVISION_REQUIRED`; Start / resume answers `409` "Validation failed for this exact candidate" | The candidate's tests fail or it does not compile | Use **Request changes** on the patch task with feedback. Re-running validation alone cannot help. |
| Run pauses with event `REVALIDATION_REQUIRED` | The candidate changed after it was validated | Use **Request changes** on a patch task so that the candidate is rebuilt and validated again. |
| Run pauses with event `REPLAN_REQUIRED` | The scenario file changed after the run started | Revise any task. That re-pins the scenario and regenerates everything. |
| Run is `SAFE_STOPPED` | A rule was broken. The `POLICY_SAFE_STOP` event says which: patch outside scope, audit chain or evidence integrity failed, model call budget exhausted | Final. Inspect the run, fix the cause, start a new run. |
| `403` "Reload the operator page before taking an action" | The page holds a token from before the factory restarted | Reload the page. |
| `403` "Local same-origin requests only" | The page was opened through another host name, a proxy or another origin | Use `http://localhost:8000/factory/` or `http://127.0.0.1:8000/factory/`. |
| Chat answers "Workflow commands require the operator token" | Run-changing commands are refused without the token | Use the operator page for that action. |
| `409` "Run is already being advanced" | Another worker or a CLI process holds the run's lease | Wait and retry. |
| `409` "Stale run revision; reload before retrying" | Another process changed the run first | Reload and retry. |
| `409` "Reviewed hash differs from the pending approval" | The proposal changed since the page loaded | Reload and review again. |
| `503` "Two runs are active; no decision was recorded" | Both workers are busy | Retry when one finishes. Nothing was saved. |
| New live run refused: "Add ANTHROPIC_API_KEY ..." or "Set FACTORY_AUDIT_KEY ..." | Live prerequisites missing | Set both in `.env`, restart. |
| Chat answers "Daily chat provider-request budget exhausted" | The chat budget for today (UTC) is used up | Wait until 00:00 UTC or raise `FACTORY_CHAT_DAILY_REQUESTS` and restart. |
| `verify-audit` prints `AUDIT_INVALID` for runs that used to verify | The audit key changed, or `.runs/audit.key` was deleted and regenerated | Restore the original key. If that is impossible, treat those chains as unverifiable. |
| Startup fails with "... must be in 1..N" or "FACTORY_AUDIT_KEY must contain at least 32 characters" | Invalid setting | Fix the named variable. |
| Launcher exits with "Invalid .env entry on line N" | A name the launchers do not accept, or a line without `=` | Remove it from `.env`; export it in the shell if it is a shell-only variable. |
| `git worktree add` fails when starting a run | A stale worktree record or a missing baseline tag | `git worktree prune`; check `git tag` lists the scenario's `baselineTag`. |
| Readiness is `DOWN` | Control database unreachable | `docker-compose ps`; start `control-db`. |

### Shortener

| Symptom | Likely cause | What to do |
| --- | --- | --- |
| `GET /{code}` answers `429` | A regression. Only creation is rate limited | Treat as a defect. `RedirectRateLimitRegressionTest` covers it. Do not widen the limiter. |
| `503 temporarily_unavailable` on lookups or creation | Database down, or a connection or statement timed out | Restore PostgreSQL. Links cached in the last 60 seconds keep redirecting meanwhile. Afterwards run `acceptance.py`. |
| Redirects work but counts do not rise | Normal lag of about one second, or flushes are failing | Look for `Analytics flush of N links failed` in the log (at most one line per 30 s). Check the database. Pending counts are retried; after a long outage some are dropped. |
| Every client gets `429` on creation behind a proxy | All requests share the proxy's address | Export `SHORTENER_FORWARD_HEADERS_STRATEGY=native` (or `framework`) only if the proxy overwrites `X-Forwarded-For`. |
| `400` "Target must not be this shortener" | The target host equals the host in `SHORTENER_BASE_URL` | Intended. Check the base URL if it is wrong. |
| `shortUrl` has the wrong host or port | `SHORTENER_BASE_URL` not set for this deployment | Set it and restart. |
| Startup fails: "shortener.base-url must be an absolute http(s) URL with a host" | Invalid base URL | Fix `SHORTENER_BASE_URL`. |
| Readiness is `DOWN` | Product database unreachable | `docker-compose ps`; start `shortener-db`. |
