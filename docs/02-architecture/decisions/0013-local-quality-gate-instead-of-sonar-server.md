# ADR 0013: A local quality gate in Maven instead of a Sonar server

**Status:** accepted

**In one paragraph.** `mvn verify` runs formatting, static analysis, security analysis, a coverage floor and platform rules for each module, and fails on any finding. It needs no server, account or token, and runs the same way on a laptop and in CI.

## Context

The project needs a consistent bar for style, likely bugs, security smells and coverage. A SonarQube or SonarCloud setup would provide that plus history, but it needs a running service or a hosted account and a token, and a fresh clone could not reproduce the result offline.

## Decision

Each module's POM runs, in the `verify` phase:

| Check | Tool | Fails when |
| --- | --- | --- |
| Formatting | Spotless with palantir-java-format, import order, `sortPom` | Any file differs from the formatter's output |
| Maintainability | PMD with the curated ruleset in `build-config/pmd-ruleset.xml` (approximates the "Sonar way" profile) | Any violation of priority 1 to 3 |
| Bugs and security | SpotBugs with FindSecBugs, effort `Max`, threshold `Medium` | Any finding |
| Coverage | JaCoCo line coverage of the module | Below `jacoco.line.minimum` (shortener 0.80, factory 0.70) |
| Platform | Maven Enforcer | Java older than 21, Maven older than 3.9, or a dependency resolved below a declared upper bound |

`-Dquality.failOnViolation=false` turns PMD and SpotBugs into reports. Spotless, JaCoCo and Enforcer always gate. Suppressions are written at the finding with a reason (`@SuppressWarnings("PMD....") // why`); the shared SpotBugs exclusion file is empty.

## Consequences

- The gate is reproducible from a clean clone with no network service.
- There is no trend history, no "new code" focus and no pull-request decoration.
- The PMD version bundled with the Maven plugin cannot read JDK 26 class files, so `verify` must run on JDK 21 to 25.
- The two modules do not inherit from the root POM, so the plugin configuration is repeated in both and must be kept in step. The rule files are shared.
- Only main sources are analysed by PMD; tests are formatted and counted for coverage but not linted.

## Revisit when

- Several people contribute and want trends or review decoration: add a Sonar scan alongside, keeping this gate as the blocking check.
