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
- [x] Simulator and record/replay with versioned, anonymized recordings (done early in M1b)
- [x] Server provider: TPS/MSPT, players, dimensions (chunks, entities, MSPT), JVM memory
- [ ] spark as optional TPS/MSPT source
- [x] Server page UI: players, admin details, collector cost, data freshness
- [x] Flux provider: networks, statistics, members, devices (incl. unloaded), membership-based access
- [x] Flux live UI: network cards, network detail (totals, balance, zones), device list with type filter and search, members
- [x] Units: FE/RF or EU per viewer with SI prefixes (fixed 4 FE = 1 EU; server-wide factor later)
- [x] i18n (de, en), app shell (sidebar on desktop, bottom bar on phone), login page, account page
  (preferences, sessions, device tokens), live SSE updates with polling fallback
- [x] PWA (manifest, icons, service worker caching only the app shell, never `/api`)
- [x] Reverse proxy guide (Caddy, nginx) and server admin guide
- [x] Forge wiring: lifecycle, tick, `overseer-server.toml`, operator check
- [x] Forge GameTest run in CI with Flux Networks

## M1b: Flux in depth

- [x] Recordings: format, anonymized export from real databases, replay provider, simulator,
  committed real-world fixture; `/ov status` for everyone

- [x] History backend: time-weighted minute/hour rollups, retention, `/api/v1/history`, `/api/v1/sessions`
- [x] Charts (Flux network flow and stored energy, server TPS/MSPT/players) with ranges 1 h-30 d,
  server uptime / calendar axis (account setting) and offline markers, smooth curves
- [x] Zones from `Zone: Name` with UI overrides (Flux owners/admins), `canEdit` permission
- [x] Energy flow: Sankey (zones → network → zones, storages by direction) with Recharts,
  compact bar list on phones; tested with the real recording; optional device detail
  (device → zone → network → zone → device, same-named devices grouped)
- [x] Mirrored stacked area chart per zone (generation up, consumption down, six largest zones + other)
- [x] Forecast: "empty in ..." from the last hour ("full in" needs storage capacity)
- [x] Admin storage page: sizes, retention per resolution (including unlimited), size
  warning, purge, VACUUM, backup download

Verified with 87 minutes of real server data (0.2.0 database migrated to schema 4, rollup of
87 min in 86 ms, history queries < 10 ms). Charts got a min-max band after the real data
showed TPS dips to ~7-10 every few minutes that averages hid.

## M2: Applied Energistics 2

Delivered in slices (docs/modules/ae2.md):

- [x] M2a: grid discovery via controller (id = lowest controller), owner access, names in
  the UI; power, inventory totals, channels and CPU usage with history; list and detail pages
- [x] M2b: FTB Teams party members see the owner's grids; CI also runs without optional mods
- [x] M2c: explicit shares (view only) managed by owners on the grid page
- [x] M2d: access requests (opaque handles, no positions), in-game chat notifications,
  allow/decline on the grid page, 24 h cool-down after a decline
- [ ] Optional "placed a block in the grid" access (server setting, default off)
- [x] M2e: grids without controller: hidden for admins, adoptable in the UI, `/ov ae2 register`
- [x] M2f: inventory scan every 10 s, stored as per-minute changes; contents list with search
  and 1 h / 24 h changes
- [ ] Watchlist every second; measure the scan cost on a real server (spread over ticks only
  if needed)
- [ ] Changes view, deficit view with forecast
- [x] M2g: crafting CPUs live (busy first), jobs with progress, elapsed time, estimate and
  history; cards expand to details and recent jobs
- [x] M2h: job lines in expanded CPU cards (crafting, planned, stored), live
- [ ] Drive and cell usage
- [ ] Item icons: optional client exporter (atlas upload) + jar texture fallback, cached by
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
