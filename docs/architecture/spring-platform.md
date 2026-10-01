# Spring platform decisions

Both deployable applications explicitly use Spring Boot 4.1.1 and Java 21. The factory imports ADK's web configuration into its own Boot composition root. ADK 1.10.1 declares Boot 4.0.2; the factory now manages the resolved Spring dependencies through the Boot 4.1.1 parent, matching the shortener. Integration and operator tests must pass before this alignment is accepted.

## Ownership and lifecycle

Spring constructs the factory service, pooled control datasource and ADK agent loader. Context shutdown closes the service before the datasource. Flyway runs before the control repository is made available. The CLI uses the same migrations, with short-lived direct JDBC connections. It remains an operator command, not a second web server.

The shortener injects its clock, URL policy, code generator and cache. Analytics has a separately managed two-connection pool and a bounded two-worker, 128-item queue. Spring destroys the recorder before its pool; redirects never wait for analytics capacity. Dropped writes are measured. This is intentionally best-effort analytics, not durable messaging.

Factory V1 is an additive adoption migration. Existing databases receive a version-zero baseline, then V1 adds any missing tables/columns without rewriting persisted run or audit JSON. Future schema changes require new migrations. PostgreSQL session advisory locks are explicitly released before returning pooled connections; an uncertain unlock aborts the physical connection.

## Library choices

| Capability | Decision and reason |
| --- | --- |
| Bootstrap, DI, configuration, HTTP | Spring Boot and MVC in both applications. |
| Security | Spring Security filter chains and default security headers. Factory keeps its loopback, same-origin, JSON and operator-token checks inside the chain. The shortener deliberately exposes public links and rate-limited creation. Neither application enables cookie authentication; default session CSRF is disabled for these explicit policies. |
| OAuth2/JWT | Not configured: no identity provider or user/role model has been specified. The local operator token is not authentication. Remote deployment requires an authenticated operator boundary and role policy first. |
| Persistence | Spring JDBC/Hikari for product queries and the factory web datasource. Explicit control transactions preserve revision checks and atomic audit snapshots. JPA/Hibernate are not added merely to wrap these SQL operations; they are appropriate if a richer entity model develops. |
| Validation | Jakarta Validation starter plus domain URL, patch, budget and approval validation. Domain safety checks remain usable outside HTTP. |
| Schema changes | Flyway in both applications; no Hibernate schema generation. |
| Resilience | Existing bounded queues, worker limits, durable budgets and deadlines. Automatic retries around provider calls or approvals could duplicate paid work or decisions; Resilience4j requires a specifically idempotent operation before adoption. |
| HTTP clients | Keep the Anthropic/ADK clients and the controlled public-web reader. Replacing the latter must preserve DNS/socket address checks and redirect authorization. |
| Cache | Bounded local cache remains sufficient for one local instance. Redis introduces an operational dependency without a current distributed workload. |
| Metrics and health | Micrometer and Actuator in both applications; only health/info exposed. Control readiness includes PostgreSQL. Existing factory run metrics remain in the operator API. |
| Tracing | ADK supplies OpenTelemetry dependencies. No external trace exporter is configured; prompts and artifacts must not become unrestricted telemetry. |
| Logging | Boot-managed SLF4J/Logback. No secrets or raw provider credentials in application logs. |
| Mapping / boilerplate | Records and explicit constructors fit this codebase. MapStruct and Lombok are unnecessary for the current small model. |
| Tests | JUnit and Mockito via Boot's test starter. Real PostgreSQL and Docker candidate validation already exist; Testcontainers is a future portability improvement, not a replacement for the untrusted-code sandbox. |
| Kafka, Batch, Cloud | Deferred: no event broker, bulk batch workload, gateway or distributed service-discovery requirement exists. |

## Human oversight remains authoritative

Spring does not authorize agent actions. The engine still verifies budgets, repository boundaries, evidence hashes and durable approval state. Applying a proposal and releasing a result require the existing human gates. Neither a successful HTTP request nor a passing health check constitutes approval or proof of generated-code quality.

## Local operation

From the repository root, use `python3 scripts/factory_web.py` and `python3 scripts/shortener.py`. These load the existing ignored `.env` through the allowlisted parser. Factory UI is at `http://localhost:8000/factory/`, ADK at `/dev-ui/?app=software_factory`, product at `http://localhost:8080`. Both provide `/actuator/health/readiness`.

Executable packaging: `mvn -pl factory,shortener -am -DskipTests package`. Run each resulting Boot jar from the repository root with the required environment variables. Plain `java -jar` does not load `.env` automatically.

## Verification — 2026-10-01

- Full Maven unit/integration run, followed by focused migration and HTTP-validation reruns: 77 tests represented in the final reports; zero failures, errors or skips.
- Six CLI fixture replays: greenfield, brownfield, ambiguous and bug-fix completed; policy violation safe-stopped; retry exhaustion failed as designed. All audit checks passed.
- Operator HTTP checks passed with typed/validated DTOs, including invalid kind/mode, missing token, cross-origin request, stale approval hash, revision, clarification and rejection.
- Chrome desktop/mobile approval and revision flows passed. Local browser evidence: `.runs/browser/2026-10-01T10-48-50.235Z/results.json`.
- Product HTTP acceptance passed; both readiness endpoints returned `UP` after restart.
- Executable Boot packages built; the packaged factory started on an alternate port and passed readiness and invalid-request checks.
- All 22 curated historical evidence checksums remain intact. No live model calls were used.
