# URL shortener

The Spring Boot product built and tested by the software factory. Its public contract is [openapi.yaml](openapi.yaml).

Read `src/main/java/dev/shortener/` by capability:

| Package | Responsibility |
| --- | --- |
| `links/` | Link creation, aliases and URL rules, together with their HTTP endpoints and PostgreSQL storage. Start with `ShortenerService`; `ShortenerController` and `ApiErrors` expose the API, and `JdbcLinkRepository` implements `LinkRepository`. |
| `redirects/` | Bounded lookup caching for immutable links. |
| `analytics/` | Best-effort recording, isolated from redirect availability. |
| `ratelimit/` | Creation-request limits. |
| `bootstrap/` | Application wiring; `ShortenerApplication` stays at the package root so Spring scans every capability. |

Keep these feature packages shallow at the current size. The service, repository contract, JDBC implementation and controller belong together in `links/`; analytics recording, lookup caching and creation throttling remain separate cohesive capabilities. Dependencies use constructor injection, with application wiring in `bootstrap/`. Add a subpackage when a feature grows enough to benefit from it, rather than creating empty domain/application/adapter layers.

The controller and HTTP integration tests also live in `links/`. Historical scenario baselines and exported evaluation evidence retain their original paths; replay them against their pinned commits.

Schema migrations are in `src/main/resources/db/migration/`; runtime configuration is in `src/main/resources/application.yml`. Tests mirror production packages under `src/test/java/dev/shortener/`.

From the repository root, start the database with `docker-compose up -d shortener-db`, then run `python3 scripts/shortener.py` (loads local database settings from `.env`) with JDK 21. Validate the running service with `python3 scripts/checks/acceptance.py`.
