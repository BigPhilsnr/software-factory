# ADR 0014: Spring Boot as the platform, and which libraries were left out

**Status:** accepted

**In one paragraph.** Both applications are Spring Boot 4.1.1 on Java 21 and let Spring own object lifecycle, HTTP, configuration, connection pools, migrations, security filters and health endpoints. Popular libraries that have no current job here (JPA, OAuth2, Resilience4j, Redis, Kafka, Testcontainers, Lombok, MapStruct) are deliberately not included. Each has a stated condition for adding it.

## Context

The factory embeds Google ADK's web server, which is itself a Spring Boot application. ADK 1.10.1 declares Boot 4.0.2; the factory manages the resolved Spring dependencies through the Boot 4.1.1 parent so that both modules run the same stack. Earlier versions of the factory created some resources by hand (direct JDBC connections, an ad-hoc schema); those are now owned by Spring.

## Decision

### Ownership and lifecycle

- **Factory web process.** `FactoryWebServer` is the composition root. Spring builds the settings, the Hikari pool, runs Flyway, then the control database beans (`@DependsOnDatabaseInitialization`), `FactoryService` and the ADK agent loader. On shutdown `FactoryService.close()` drains the run workers (30 s, then interrupt) and removes its own validator containers before the pool closes.
- **Factory CLI.** A plain `main` method with no Spring context. It opens direct connections and runs the same migrations.
- **Shortener.** Spring injects the clock, URL policy, code generator, cache and rate limiter. The analytics recorder is a `SmartLifecycle` that stops after the web server has drained and before its pool is destroyed.
- **Human gates stay in the engine.** Spring Security lets every request through to the application (`permitAll`) after the local-operator filter; approvals, budgets and integrity checks are enforced by `RunEngine`, not by the framework. A passing health check or a `200` response is never an approval.

### Library choices

| Capability | Choice and reason |
| --- | --- |
| DI, configuration, HTTP | Spring Boot and Spring MVC in both applications. |
| Security | Spring Security filter chains with default hardening headers. Factory: loopback, same-origin and operator-token checks plus a strict Content-Security-Policy. Shortener: the chain guards `/actuator/**` only. No cookies, no sessions. |
| Authentication (OAuth2, JWT) | Not included. There is no identity provider or role model to integrate. The operator token is not authentication. Required before any non-local deployment. |
| Persistence | Spring JDBC and Hikari, hand-written SQL. See [ADR 0001](0001-plain-sql-over-spring-data-jpa.md). |
| Schema | Flyway. See [ADR 0005](0005-flyway-for-both-databases.md). |
| Validation | Jakarta Validation on request records and settings, plus domain checks (`UrlPolicy`, `PatchScope`) that also work outside HTTP. |
| Resilience | Bounded queues, timeouts, budgets and one recorded retry, all in application code. Resilience4j is not included: automatic retries around paid model calls or approvals could repeat them. |
| Cache | Caffeine, in process. Redis is not included. See [ADR 0002](0002-hand-rolled-rate-limiter-and-caffeine-cache.md). |
| HTTP clients | The Anthropic SDK (OkHttp) and one controlled OkHttp client for public pages, whose DNS resolver refuses non-public addresses. |
| Health and metrics | Actuator and Micrometer. Only `health` and `info` are reachable over HTTP in either application. |
| Tracing | ADK brings OpenTelemetry libraries; no exporter is configured. Prompts and artifacts must not become telemetry. |
| Logging | SLF4J and Logback. Factory logs carry run and task ids; shortener logs carry the request id. |
| Mapping and boilerplate | Java records and constructors. Lombok and MapStruct are not included. |
| Tests | JUnit 5, Mockito, REST Assured, Awaitility, ArchUnit. Integration tests use the Compose databases with a throwaway schema per suite. Testcontainers is not included. |
| Messaging, batch, cloud | Not included. Nothing needs a broker, a batch framework or service discovery. |

## Consequences

- One framework version to patch. The enforcer's upper-bound rule catches dependency downgrades, with four documented exclusions inside ADK's own graph.
- Integration tests need the two local databases to be running; they are not self-starting.
- The factory process includes the whole ADK web stack, including its developer UI, which needs a looser Content-Security-Policy than the operator page.

## Revisit when

- **Remote or multi-user access:** add authentication and roles first.
- **A second shortener instance:** shared rate limiting and cache.
- **Contributors without the Compose databases, or CI without service containers:** Testcontainers.
- **An idempotent external call that should be retried automatically:** Resilience4j around that call only.
