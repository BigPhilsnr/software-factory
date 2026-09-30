# URL shortener

The Spring Boot product built and tested by the software factory. Its public contract is [openapi.yaml](openapi.yaml).

Read `src/main/java/dev/shortener/` by capability:

| Package | Responsibility |
| --- | --- |
| `http/` | HTTP endpoints and error responses: `ShortenerController`, `ApiErrors`. |
| `links/` | Link creation, aliases, URL validation, code allocation and the storage contract. Start with `ShortenerService`. |
| `redirects/` | Bounded lookup caching for immutable links. |
| `analytics/` | Best-effort recording, isolated from redirect availability. |
| `ratelimit/` | Creation-request limits. |
| `storage/` | PostgreSQL implementation of the link repository. |
| `bootstrap/` | Application wiring; `ShortenerApplication` stays at the package root so Spring scans every capability. |

Schema migrations are in `src/main/resources/db/migration/`; runtime configuration is in `src/main/resources/application.yml`. Tests mirror production packages under `src/test/java/dev/shortener/`.

From the repository root, start the database with `docker-compose up -d shortener-db`, then run `mvn -f shortener/pom.xml spring-boot:run` with JDK 21. Validate the running service with `python3 scripts/checks/acceptance.py`.
