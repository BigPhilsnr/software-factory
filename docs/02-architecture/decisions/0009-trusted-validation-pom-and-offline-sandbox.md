# ADR 0009: A trusted validation POM and an offline, locked-down sandbox

**Status:** accepted

**In one paragraph.** Candidate tests run in a Docker container with no network, a read-only filesystem and no capabilities. Maven is driven by a POM that ships inside the factory, not by the candidate's own `pom.xml`, and writes its test reports to a folder on the host that the candidate does not control beforehand. The verdict comes from those report files.

## Context

Running a candidate's tests means running code a model wrote. A Maven build can also execute arbitrary plugins declared in the candidate's POM. The validator must not leak credentials, reach the network, or be told "all tests passed" by build logic the candidate supplied.

## Decision

- **Image:** `maven@sha256:99e61abc...8320`, pinned by digest in `SandboxValidator.IMAGE`. CI checks that the workflow and the source agree.
- **Isolation** (`DockerSandbox`): `--network none`, `--read-only`, `--cap-drop ALL`, `--security-opt no-new-privileges`, `--cpus 2`, `--memory 1g`, `--pids-limit 128`, the host user's uid and gid, a 128 MB `/tmp`, a 10-minute timeout. The container gets no environment from the factory.
- **Mounts:** the candidate read-only at `/workspace`; `shortener/target` inside it writable; a fresh host directory at `/reports`; the trusted POM read-only at `/trusted/pom.xml`; the host's Maven repository read-only at `/m2`.
- **Build definition:** `validation/shortener-pom.xml` mirrors the shortener's dependencies and test selection but fixes every directory and the report location. Maven runs offline (`-o`). `ShortenerValidationPomDriftTest` fails when the two POMs drift apart.
- **Verdict** (`SandboxValidator`, `SurefireReports`): exit code 0, at least one test, zero failures, errors and skips, read from Surefire XML. Reports must be regular files newer than the start of the run. Every test class changed by an upstream patch must have executed at least one test. Platform problems (Docker down, image missing, cache incomplete) are classified separately and pause the run instead of failing the candidate.
- **Clean-up:** each container has a unique name and an owner label; it is removed in a `finally` block, and orphans are swept at startup and shutdown.

## Consequences

- A candidate cannot add build plugins, change test selection or redirect reports through its POM. Changes to the candidate's `pom.xml` have no effect on validation; a new dependency needs a reviewed update of the trusted POM.
- Validation only covers the `shortener` module.
- The host must have built the shortener once with network access, so that `~/.m2` holds every artifact the offline build needs.
- **Not covered:** test code runs inside the container and `/reports` is writable there. A hostile test could overwrite or forge its own report. The sandbox protects the host; it does not make the test verdict trustworthy against the code under test. Human review of the test patch is the control for that.
- A container is not a perfect security boundary.

## Revisit when

- Candidates must be allowed to change dependencies: generate the trusted POM from a reviewed allowlist.
- Verdicts must hold against hostile test code: run a second, factory-owned acceptance suite against the built candidate from outside the container.
