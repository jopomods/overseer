# Roadmap

Milestones are cut so that each one ends with something usable. Decisions behind the
scope are in the [ADRs](adr/README.md).

## M0: Foundation

- [x] Repository renamed to `overseer`, default branch `main`, squash-only merges
- [x] Gradle multi-project: `core`, `web`, `platform/forge-1.20.1` (ModDevGradle Legacy)
- [x] Draft `DataProvider` contract, `Timestamp`, `ResourceRef`
- [x] Web scaffold: Vite, React, TypeScript, Tailwind, shadcn/ui, TanStack Query
- [x] Contract-first `api/openapi.yaml` with generated TypeScript client
- [x] Playwright layout tests at 360/390/768/1024/1280/1920 px with overflow detection
- [x] Placeholder pixel-art logo
- [x] Docs: AGENTS.md, architecture, glossary, ADRs 0001-0013
- [x] CI (build, tests, lint, layout tests, PR title lint) and release-please

## M1a: Base → v0.2.0

0.1.0 was released early from the core-only state (pipeline, storage, HTTP, auth); the
complete M1a ships as 0.2.0. Delivered as several small PRs: pipeline + storage, HTTP + auth + permissions, simulator + UI shell, Flux + server providers.

- [x] Snapshot pipeline: tick collector with budgets, bounded queue, latest-state cache (core)
- [x] SQLite: WAL, single writer, migrations, uptime sessions, events and metrics with deadband and heartbeat
- [x] Netty HTTP: router, REST, SSE, static UI, size limits, health endpoint (embedded via Jar-in-Jar)
- [x] Auth: link codes, sessions (sliding, HttpOnly cookie), device tokens, console recovery,
  "log out everywhere", rate limiting, hashed secrets (`/ov` commands, console and RCON)
- [x] Permissions: global roles, provider policies, default deny, tests, re-check on SSE push
- [ ] Simulator providers with edge cases; record/replay with versioned files
- [x] Server provider: TPS/MSPT, players, dimensions (chunks, entities, MSPT), JVM memory
- [ ] spark as optional TPS/MSPT source
- [x] Server page UI: players, admin details, collector cost, data freshness
- [x] Flux provider: networks, statistics, members, devices (incl. unloaded), membership-based access
- [x] Flux live UI: network cards, network detail (totals, balance, zones), device list with type filter and search, members
- [x] Units: FE/RF or EU per viewer with SI prefixes (fixed 4 FE = 1 EU; server-wide factor later)
- [x] i18n (de, en), app shell (sidebar on desktop, bottom bar on phone), login page, account page
  (preferences, sessions, device tokens), live SSE updates with polling fallback
- [ ] PWA (manifest, service worker caching only the app shell)
- [x] Reverse proxy guide (Caddy, nginx) and server admin guide
- [x] Forge wiring: lifecycle, tick, `overseer-server.toml`, operator check
- [x] Forge GameTest run in CI with Flux Networks

## M1b: Flux in depth

- History with rollups; charts on server uptime / calendar time with offline markers
- Zones from `Zone: Name` with UI overrides
- Sankey (prototype with recorded data first; compact list on phones) and mirrored
  stacked area chart
- Forecast: "buffer empty/full in ..."
- Admin storage page: sizes, retention per resolution (including unlimited), size
  warning, purge, VACUUM, backup/export

## M2: Applied Energistics 2

- Automatic grid discovery via controller; ad-hoc grids hidden, adoptable by admins;
  `/ov ae2 register`
- Access: controller owner, FTB team, explicit shares, optional "placed a block in the
  grid"; access requests with in-game chat notification
- Inventory scan every 10 s (cost measured, spread over ticks only if needed), stored
  as deltas; watchlist every second
- Changes view, deficit view with forecast
- Crafting CPUs (named first), jobs with progress and history, power, drive and cell usage
- Item icons: optional client exporter (atlas upload) + jar texture fallback, cached by
  item id + NBT hash

## M3+

- Tom's Storage
- GTCEu as its own bounded module (scope to be defined)

## Backlog

- Built-in HTTPS via Let's Encrypt (ACME)
- Recipe export
- Notifications (push, Discord webhook)
- Custom dashboard widgets
- Control actions (write access; requires CSRF protection)
- More loaders and versions (NeoForge, Fabric) via Stonecutter, only when needed
- Public release (GitHub, Modrinth/CurseForge, MIT license)
- Final logo
- Upgrade web to TypeScript 7 once `openapi-typescript` supports it (pinned to 5.9, see ADR-0009)
