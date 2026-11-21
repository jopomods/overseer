# 0023. CI/CD rework: faster jobs, workflow security, lean releases

- Status: Accepted
- Date: 2026-11-20
- Builds on [ADR-0015](0015-ci-suites-and-path-rules.md) (suites, path rules, one status, remembered
  passes), which stays in force; amends the release part of [ADR-0013](0013-versioning-and-releases.md).

## Context

The repository went public on 2026-11-20, after the private plan's 2,000 Actions minutes ran out
in November (about 23 billed minutes per full run, 41 % of them on pushes to `main`). Minutes are
free now, but a PR still waited 2.5-3 minutes, and a GameTest job spent about 75 s of which 1.5 s
were tests: the rest was runner setup, Gradle configuration (about 16 s), the client asset check
and the Forge server start. The web jobs pulled a 2 GB Playwright container (about 20 s) before
doing anything. The release workflow repeated every browser test with `--with-deps` (several
minutes of apt). The workflows themselves had no lint, and permissions were granted per workflow.

How large mod projects run CI (AE2, GregTech-Modern, Create, Mekanism, Fabric API, Botania, Refined
Storage 2): one job builds and runs one GameTest server with everything; `gradle/actions/setup-gradle`
caches Gradle, written on the default branch only; Mekanism keeps the configuration cache with an
encryption key; Fabric and Botania validate the wrapper. None splits GameTests by mod set, which
ADR-0015 does on purpose (a failing AE2 test must not hide Flux).

## Decision

Fewer and faster jobs, not more runners:

- **Configuration cache in CI.** `java` and `gametest` run Gradle with `--configuration-cache`;
  `setup-gradle` stores the entry encrypted (`GRADLE_ENCRYPTION_KEY`, see the
  [repository settings](../guides/repository-settings.md)) on `main` and PRs read it. Not in
  `gradle.properties`: `gameTestAll` configures one nested build per suite while it executes, which
  the configuration cache rejects.
- **`base` and `profiler` share one server** in CI as the existing `bare` suite: both load no
  optional mod, so isolation is unchanged. The path rules still name the suites; `classify.py`
  maps them to CI runs (`bare`, `flux`, `ae2`, `teams`, `ae2-teams`).
- **Web tests on the runner**, not in the Playwright container: the runner image already has the
  browser's system libraries, and the browser binary is cached per Playwright version.
- **Composite actions** for what every job repeats: `setup-java-gradle`, `already-passed`,
  `remember-pass` (`.github/actions/`).
- **Workflow security.** `permissions: {}` at the top of every workflow, each job asks for what it
  needs; `persist-credentials: false` on every checkout; actions pinned to SHAs (also required by
  the repository); a `Workflow lint` job (actionlint with shellcheck, zizmor) runs when a workflow
  or action changes and on every push to `main`, and is part of `CI ok`; CodeQL also scans the
  workflows; Dependabot waits seven days before proposing a new release (cooldown).
- **Dependency graph**: `dependency-graph.yml` submits the resolved Gradle dependencies on `main`
  when build files change, so Dependabot alerts cover transitive Gradle dependencies.
- **Lean release.** The release commit already passed CI as a PR and on `main`, so the release job
  builds the jar (with unit tests, without Gradle caches) and attaches it with `SHA256SUMS.txt` and a
  signed build provenance attestation (`gh attestation verify`). No second browser run.
- **Manual runs and timings.** `workflow_dispatch` runs everything and ignores remembered passes;
  `.github/ci/timings.py <run id>...` prints wall time, job time and billed minutes per run.

Tried and dropped: skipping `downloadAssets` for the GameTest server (about 6 s). It works locally,
but ModDevGradle's `prepareGameTestServerRun` needs the asset properties file that task writes.

## Results

Same pipeline scope (every job), measured with `timings.py`:

| | Wall time | Jobs | Job time | Billed minutes |
|---|---|---|---|---|
| Before (PR #104, run 44772203942) | 2.8 min | 12 | 15.4 min | 23 |
| After (PR #105, run 44778237270), configuration cache not yet on `main` | 2.0 min | 12 (incl. workflow lint) | 11.1 min | 18 |

The slowest job is still a web shard (about 1.5-2 min, of which 50-90 s are Playwright tests);
that is where further gains have to come from, not from Java.

## Consequences

- A PR waits about 2 minutes instead of 3; GameTest and Java jobs gain another 10-15 s each once
  `main` has stored configuration cache entries.
- A missing or rotated `GRADLE_ENCRYPTION_KEY` only costs speed. PRs from forks get no
  configuration cache (no secrets), which is fine.
- Workflow changes must pass actionlint, shellcheck and zizmor; `.github/zizmor.yml` documents the
  one disabled rule.
- Release jars are verifiable; a release no longer re-runs the browser tests, so a broken release
  can only come from a commit that CI let through on `main`.

## Alternatives considered

- **One GameTest server with all mods** (AE2, Create, GTM do this): fewer jobs, but failures hide
  each other and optional mods are never tested absent (AGENTS.md rule 3). Kept ADR-0015's suites.
- **All suites in one job, one after another**: one setup instead of five, but about 2.5 minutes of
  wall time; parallel jobs are free on a public repository.
- **Blacksmith or larger runners**: faster hardware, but not needed for 2 minutes, and code would
  run on a third party.
- **Configuration cache for every local build**: blocked by `gameTestAll`'s nested builds; revisit
  if those become plain tasks.
