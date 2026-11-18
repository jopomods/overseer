# AGENTS.md

Entry point for anyone (human or agent) working on Overseer. Read this first, then
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Decisions and their reasons live in
[docs/adr/](docs/adr/README.md). Terms are defined in [docs/GLOSSARY.md](docs/GLOSSARY.md).
Milestones and the backlog are in [docs/ROADMAP.md](docs/ROADMAP.md).

## What this is

Overseer is a server-side Minecraft mod with an embedded web dashboard. It collects
live data from the server and from other mods (Flux Networks and AE2 today; GTCEu and Tom's
Storage planned, one milestone each),
stores history in SQLite, and serves a React UI that works on phones. Players only
see what the mods' own permission rules let them see.

## Repository layout

| Path | What | Rules |
|---|---|---|
| `core/` | Plain Java 17: provider contract, data model, storage, web server, auth, permissions | **No Minecraft, Forge or mod imports. Ever.** Testable without a game. |
| `platform/forge-1.20.1/` | Forge 1.20.1 mod: entry point, tick collector, commands, mod adapters | Only place that touches Minecraft or mod APIs. Keep adapters thin. Mixins only to fix what Forge or Minecraft get wrong, each with an ADR (ADR-0014). |
| `web/` | Vite + React + TypeScript + shadcn/ui dashboard | Built into the jar under `/overseer-web`. |
| `api/openapi.yaml` | HTTP API contract | Change this first; regenerate the TS client. |
| `docs/` | Architecture, ADRs, glossary, roadmap, guides | Updated in the same PR as the code. |

Package root: `io.github.jopojp.overseer` (`.core`, `.forge`).

Per-integration docs: [docs/modules/](docs/modules/). New integration pattern: a `*Reader`
that alone touches the mod API and copies into plain records, a pure `*Mapping` that
builds resources and access rules (unit-tested), a thin `*Provider`, a factory entry in
`Integrations` behind `ModList.isLoaded`, and a GameTest class registered in a suite of its own in
`GameTestSuites` and `build.gradle.kts` (both tables), plus a suite for each integration it depends on.

## Commands

```sh
./gradlew build                          # everything: core tests, web build, mod jar
./gradlew build -PskipWeb=true           # fast Java-only build
./gradlew :core:test                     # core unit tests
./gradlew :platform:forge-1.20.1:runServer   # Forge dev server (run dir: platform/forge-1.20.1/run)
./gradlew gameTestAll                    # every GameTest suite, each in its own server and world (before pushing)
./gradlew :platform:forge-1.20.1:runGameTestServer -PgametestSuite=ae2   # one suite: base, profiler, flux, ae2, teams, ae2-teams
./gradlew :platform:forge-1.20.1:runGameTestServer   # all tests in one server with all mods (exits non-zero on failure)
./gradlew :platform:forge-1.20.1:runGameTestServer -PbareMods=true   # without Flux/AE2/FTB Teams (rule 3)
./gradlew :platform:forge-1.20.1:test        # platform unit tests (pure mapping code)
./gradlew :core:runSimulator                 # Overseer without Minecraft, replaying fixtures/recordings
./gradlew :core:exportRecording --args="<db> <out.jsonl>"   # anonymized recording (docs/guides/recordings.md)

cd web
npm run dev          # UI dev server, proxies /api to 127.0.0.1:8765 (override: OVERSEER_API)
npm run dev:mock     # UI without any server: realistic data from fixtures/recordings, live updates
npm run dev:mock:extreme   # same with extreme data (long names, 200 devices)
npm run typecheck
npm run lint
npm run gen:api      # regenerate src/api/schema.gen.ts from api/openapi.yaml
npm run test:e2e     # Playwright layout + flow tests at 360-1920 px (port 4391), API mocked by web/mock/backend.ts
node e2e/screenshots.manual.ts <dir>   # PNGs of key pages for visual review (needs `npm run preview -- --port 4391`)
```

The mod jar lands in `platform/forge-1.20.1/build/libs/overseer-<version>+forge-1.20.1.jar`.

Toolchain: JDK 17 (Minecraft 1.20.1 requirement), Node 24, Gradle via wrapper.

## Hard rules

1. **World access only on the server thread**, only inside `DataProvider.collect()`.
   Everything else reads immutable snapshots ([ADR-0004](docs/adr/0004-threading-and-snapshots.md)).
2. **core stays Minecraft-free** ([ADR-0002](docs/adr/0002-module-structure.md)).
3. **Optional mods are optional.** An integration whose mod is absent must never be
   class-loaded. Guard with `ModList.get().isLoaded(...)` before touching the adapter class.
4. **Default deny.** Every API route and every SSE push checks permissions
   ([ADR-0008](docs/adr/0008-permissions.md)).
5. **Identity never depends on names.** Resources are keyed by stable ids
   ([ADR-0005](docs/adr/0005-data-model.md)).
6. **Every page passes the layout tests** at all widths. Add new routes to
   `web/e2e/layout.spec.ts`.
7. **API changes start in `api/openapi.yaml`**, then `npm run gen:api` and commit the
   regenerated file.
8. **Docs move with code.** A change that alters behavior, structure or a decision
   updates `docs/` (and adds or supersedes an ADR) in the same PR.

## Git workflow

- Branch from `main`: `feat/...`, `fix/...`, `chore/...`, `docs/...`.
- Commit early and often in small steps using
  [Conventional Commits](https://www.conventionalcommits.org/):
  `feat(core): ...`, `fix(web): ...`, `docs: ...`, `build: ...`, `ci: ...`,
  `test: ...`, `refactor: ...`, `chore: ...`. Scopes: `core`, `forge`, `web`, `api`,
  `flux`, `ae2`, `ci`, `docs`.
- **One mod integration per milestone.** A new mod integration (GTCEu, Tom's Storage, ...)
  is its own milestone in [docs/ROADMAP.md](docs/ROADMAP.md), released on its own; it never
  rides along with another integration or with refinement work. Milestones without a new mod
  (refinements, server features) are separate too.
- **Small PRs by default.** Split a milestone into PRs per coherent slice (e.g. pipeline +
  storage, then HTTP + auth, then UI + integration), each green and reviewable on its own.
  A follow-up slice may branch off the previous one while it is in review; rebase it onto
  `main` after the squash merge (`git rebase --onto main <old-base>`).
- Open a PR into `main`. CI must be green. Merge with **squash**; the PR title becomes
  the commit on `main`, so it must itself be a Conventional Commit (CI checks it).
- Never push directly to `main`.

## Versioning and releases

- [SemVer](https://semver.org/) for Overseer itself, independent of Minecraft. The version
  lives in `version.txt` and is managed by release-please. Never edit it by hand.
- Before 1.0.0: `feat` bumps MINOR, `fix` bumps PATCH, breaking changes bump MINOR.
  From 1.0.0: `feat!`/`BREAKING CHANGE` bumps MAJOR.
- Jar name: `overseer-X.Y.Z+<loader>-<mc>.jar`, e.g. `overseer-0.3.1+forge-1.20.1.jar`.
- release-please keeps a release PR open on `main`. Merging it tags the version, runs
  the full build and attaches the jar to a GitHub release
  ([ADR-0013](docs/adr/0013-versioning-and-releases.md)).
- Merge the release PR only when a milestone (or a deliberate patch) is complete, not
  after every slice.

## Language

Code, comments, commits, docs: English. UI text: German and English (i18n).
