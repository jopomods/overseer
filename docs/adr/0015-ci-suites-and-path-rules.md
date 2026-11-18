# 0015. CI: GameTest suites in parallel, path rules, one status

- Status: Accepted
- Date: 2026-11-18

## Context

CI ran every check for every change: the Java build, the web tests at six widths and all GameTests
in one Forge server with every optional mod. A docs-only PR took over ten minutes, and a failing
AE2 test hid whether Flux still worked. The repository is private for now, so runner minutes are
limited (2,000 per month).

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
for jobs the change does not need, never for jobs it does. Branch protection (once the repository
is public) requires only this status.

**Speed.** GameTest jobs do not wait for the build job; each compiles what it needs. Flow tests
run at 360 and 1280 px; the layout tests run at all six widths (ADR-0009). Actions are pinned to
commit SHAs.

## Consequences

- Docs PRs finish in seconds; a Flux change runs the Java build, `base` and `flux`.
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
