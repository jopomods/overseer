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

## M1a: Base → v0.1.0

- Snapshot pipeline: tick collector with budgets, bounded queue, latest-state cache
- SQLite: WAL, single writer, migrations, events and metrics with deadband and heartbeat
- Netty HTTP: router, REST, SSE, static UI, size limits, health endpoint
- Auth: link codes, sessions (sliding, HttpOnly cookie), device tokens, console recovery,
  "log out everywhere", rate limiting, hashed secrets
- Permissions: global roles, provider policies, default deny, tests, re-check on SSE push
- Simulator providers with edge cases; record/replay with versioned files
- Server page: TPS/MSPT (spark if present), players, admin details, collector cost,
  data freshness
- Flux live: network cards, network detail with current values, device table, members
- Units: FE/RF or EU per player (server-wide factor, default 4 FE = 1 EU), SI prefixes
- i18n (de, en), app shell (sidebar on desktop, bottom bar on phone), PWA without
  caching private API responses
- Reverse proxy guide (Caddy, nginx)
- Forge GameTest run in CI with Flux Networks

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
