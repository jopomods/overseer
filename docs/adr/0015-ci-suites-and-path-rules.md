# 0015. CI: GameTest suites in parallel, path rules, one status

- Status: Accepted (extended by [ADR-0023](0023-ci-cd-rework.md): `base` and `profiler` share one
  CI server, configuration cache, workflow lint)
- Date: 2026-11-18

## Context

CI ran every check for every change: the Java build, the web tests at six widths and all GameTests
in one Forge server with every optional mod. A docs-only PR took over ten minutes, and a failing
AE2 test hid whether Flux still worked. The repository was private then, so runner minutes were
limited (2,000 per month). Since 2026-11-20 it is public: standard GitHub runners are free.

## Decision

**GameTest suites.** Every integration has its own suite with only the mods it needs
(`GameTestSuites`, and the same table in `platform/forge-1.20.1/build.gradle.kts`):

| Suite | Mods | Tests |
|---|---|---|
| `base` | none | server, icons |
| `profiler` | none | tick profiler |
| `flux` | Flux Networks | server, Flux |
| `ae2` | AE2 | server, AE2, icons |
| `teams` | FTB Teams | server, teams |
| `ae2-teams` | AE2, FTB Teams | server, AE2 with teams |

Each suite runs in its own server and flat world (`run/gametest-<suite>`). CI runs the needed
suites as parallel jobs; locally `./gradlew gameTestAll` runs them one after another. A new
integration adds a suite and a rule (AGENTS.md).

**Path rules.** `.github/ci/classify.py` maps the changed paths of a PR to the jobs it needs
(fixtures in `test_classify.py`):

- docs, top-level Markdown, images: nothing but the PR title check;
- build files, CI, `core/`, `api/openapi.yaml`, unknown paths: everything;
- `web/`: web tests and the build;
- an integration's code or GameTest class: its suites (e.g. Flux: `base`, `flux`);
- other platform code: all suites.

Pushes to `main` always run everything. The rules ran in shadow mode first (everything ran, the
summary showed what would have been skipped); on the PRs of 2026-11-18 (#61-#66) they matched what
a reviewer would have run, so they are active.

**One status.** `CI ok` checks every job's result against what the change needs: skipped is fine
for jobs the change does not need, never for jobs it does. The PR title check is a workflow of its
own (`pr-title.yml`), so editing a title or description does not rerun CI; CI itself runs on
opened, pushed and reopened PRs. The ruleset on `main` requires these two checks
([repository settings](../guides/repository-settings.md)).

**Remembered passes.** The build, web and every GameTest job first look for a cache entry keyed
by the hash of their inputs (`.github/`, `core/`, `platform/`, `api/`, Gradle files, `version.txt`,
fixtures; the web job: `web/`, the API contract, recordings). If exactly these inputs already
passed, the job skips its steps and reports success; after a pass it stores an empty entry.
Runs read their own branch's entries and `main`'s, so re-pushes to a PR and later pushes to
`main` skip what has not changed. `PASS_CACHE` in `ci.yml` (bump it) or deleting the caches
forgets every pass.

**Speed.** GameTest jobs do not wait for the build job; each compiles what it needs. The layout
tests run at all six widths (ADR-0009), the edit specs at 360 and 1280 px, the flow and tier specs
at 1280 px only (tags `@desktop` and `@phone`, so they are not even scheduled elsewhere). The
browser tests run in six parallel shards (they are CPU-bound; lint, types and the API check run in
the first shard) directly on the runner, see [ADR-0023](0023-ci-cd-rework.md) for the shard count
and why there is no Playwright container any more. Actions are pinned to commit SHAs.

## Consequences

- Docs PRs finish in seconds; a Flux change runs the Java build, `base` and `flux`.
- A test that depends on something outside its hashed inputs (a runner image, a download) is not
  rerun when only that changes; bump `PASS_CACHE` then.
- A wrong rule can skip a needed test. Mitigation: unknown paths run everything, the classifier
  has fixtures, and `main` always runs everything.
- Runner minutes per full run grow with the number of suites (each job pays setup time); the path
  rules keep most runs small.

## Alternatives considered

- **One server with all mods** (before): simplest, but slow and failures hide each other.
- **Reusing a pre-generated world** between runs: the 64 s of world generation came from a
  misplaced `server.properties` (normal terrain instead of flat); with a flat world generation
  takes about a second, so a cache would only add stale state.
- **Third-party runners (Blacksmith)**: faster hardware, but code runs on a third party; not
  needed once the repository is public (free GitHub runners) and the path rules apply.
