# Quality gate

**In one paragraph.** `mvn verify` is the gate. On top of the tests it checks formatting, runs two static analysers, enforces a line-coverage floor and checks the build platform. Any finding fails the build. Everything runs locally from the module's own POM; there is no analysis server. The reasoning is in [ADR 0013](../02-architecture/decisions/0013-local-quality-gate-instead-of-sonar-server.md).

## What runs

| Check | Tool and version | Fails the build when | Configuration |
| --- | --- | --- | --- |
| Formatting | Spotless 3.10.3 with palantir-java-format 2.100.0, unused-import removal, import order, `sortPom` | A Java file or POM differs from the formatter's output | Module POMs; `.editorconfig` mirrors it for editors |
| Maintainability | PMD (maven-pmd-plugin 3.28.0), main sources only | A violation of priority 1, 2 or 3 | [`build-config/pmd-ruleset.xml`](../../build-config/pmd-ruleset.xml) |
| Bugs and security | SpotBugs (plugin 4.10.4.1) with FindSecBugs 1.14.0, effort `Max`, threshold `Medium` | Any finding | [`build-config/spotbugs-exclude.xml`](../../build-config/spotbugs-exclude.xml) (currently empty) |
| Coverage | JaCoCo 0.8.15, line coverage of the whole module | Below 0.80 (shortener) or 0.70 (factory) | `jacoco.line.minimum` in each POM |
| Platform | Maven Enforcer | Java older than 21, Maven older than 3.9, or a dependency resolved below a declared upper bound | Module POMs |

`mvn test` runs only the enforcer rules and the JaCoCo report from this list, so the edit-test loop stays fast. The other checks are bound to the `verify` phase.

## Commands

```sh
mvn spotless:apply                               # fix formatting in both modules and the root POM
mvn -f shortener/pom.xml verify                  # full gate, one module
mvn -f factory/pom.xml verify
mvn verify                                       # both modules, plus the root POM's formatting
mvn -Pintegration verify                         # gate plus integration tests
mvn verify -Dquality.failOnViolation=false       # report PMD and SpotBugs findings without failing
```

Reports are written to each module's `target/` directory:

| Report | File |
| --- | --- |
| PMD | `target/pmd.xml` |
| SpotBugs | `target/spotbugsXml.xml` |
| Coverage | `target/site/jacoco/index.html` (and `jacoco.csv`, `jacoco.xml`) |
| Tests | `target/surefire-reports/` |

## Which JDK

| JDK | `mvn test` | `mvn verify` |
| --- | --- | --- |
| 21 to 25 | yes | yes |
| 26 and newer | yes | no: the PMD release bundled with the Maven plugin cannot read JDK 26 class files |
| older than 21 | no: the enforcer stops the build | no |

The code is compiled for Java 21 (`--release 21`) whichever JDK builds it. CI uses Temurin 21.

On macOS, to pick a suitable JDK for one shell:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

## Rules for suppressions

- Suppress at the finding, with the rule name and a reason on the same line:
  `@SuppressWarnings("PMD.AvoidCatchingGenericException") // Boundary: every task failure becomes a recorded outcome.`
- Do not widen the shared rule files to hide one finding. `spotbugs-exclude.xml` is empty and should stay close to that.
- Rules that are switched off for the whole code base are listed in `pmd-ruleset.xml`, each with the reason.

## Things to know

- **Two modules, two gates.** `factory/` and `shortener/` do not inherit from the root POM. Each declares the same plugins and versions; the rule files in `build-config/` are shared. When you change a plugin version, change it in both.
- **The root POM** only lists the modules and formats itself.
- **`quality.failOnViolation`** defaults to `true` in both POMs. It affects PMD and SpotBugs only. Spotless, JaCoCo and the enforcer always gate.
- **Coverage with integration tests** is higher than the figure the gate checks in a plain `verify`, because the integration profile adds tests to the same run. The floors are set for the plain run.
- **Enforcer exclusions.** The factory excludes four artifacts from the upper-bound rule (Guava, Arrow format, FlatBuffers, animal-sniffer annotations). All four conflicts are inside Google ADK's own dependency graph.

Current results are in the [scorecard](scorecard.md).
