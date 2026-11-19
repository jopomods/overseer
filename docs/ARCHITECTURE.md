# Architecture

This document describes how Overseer is put together. The reasoning behind each choice
is in the [ADRs](adr/README.md); this page is the map.

## Modules

```
overseer/
├── core/                    Plain Java 17. No Minecraft.
│   ├── provider/            DataProvider contract, snapshots, metrics, access policy
│   ├── time/                Timestamp, UptimeClock (wall clock + server uptime)
│   ├── pipeline/            collector scheduler (budgets, throttling), bounded queue, latest state,
│   │                        InventoryState (newest inventory scan, job lines, live detail tables)
│   ├── storage/             SQLite: migrations, sessions, single writer, deadband/heartbeat,
│   │                        time-weighted rollups + retention, history queries; inventory changes
│   │                        (per minute), watchlist, icons, registrations, shares, access requests,
│   │                        notifications, profiler settings
│   ├── auth/                link codes, console login tokens, sessions, device tokens, roles, rate limits
│   ├── access/              AccessService: admin bypass + provider policies + shares, default deny
│   ├── http/                Netty server, router, SSE hub, static UI, origin check, proxy handling
│   ├── api/                 /api/v1 routes and JSON DTOs (mirror api/openapi.yaml)
│   ├── OverseerRuntime      wires everything; platforms call start(), tick(), close()
│   ├── recording/           recording format (JSON Lines), exporter from SQLite, anonymizer, replay provider
│   └── sim/                 Simulator: OverseerRuntime + replayed recordings, no Minecraft
├── platform/forge-1.20.1/   Forge mod. The only code that touches Minecraft.
│   ├── OverseerForge        entry point, server-only display test, config registration
│   ├── ServerLifecycle      starts OverseerRuntime on server start, ticks it, closes on stop
│   ├── OverseerConfig       overseer-server.toml (bind, port, proxies, public URL)
│   ├── ForgeOperatorCheck   op lookup on the server thread, cached for HTTP threads
│   ├── command/             /ov commands (also console and RCON)
│   ├── integration/         Integrations (provider list) + one package per source: server, profiler,
│   │                        flux, ae2 (Reader -> Model -> Mapping -> Provider), teams (FTB Teams)
│   ├── icons/               icon network channel (optional on both sides), mod jar texture fallback
│   ├── client/              client-only: /ovicons export renders item and fluid icons
│   ├── mixin/               completes Forge 1.20.1's block entity timer (ADR-0014); mixin/ae2: pattern
│   │                        provider icons and flow counting, only with AE2 (ADR-0018, ADR-0020, compat/ plugin)
│   └── src/gametest/        Forge GameTests (own source set, not shipped)
├── web/                     Vite + React + shadcn/ui, built into the jar at /overseer-web
│   ├── src/api/             generated schema, typed client, query hooks, SSE live updates
│   ├── src/lib/             i18n (de/en), preferences, formatting (SI, FE/EU), snapshot helpers
│   ├── src/components/      app shell (sidebar / bottom nav), stats, charts, Sankey, AE2 sections
│   │                        (contents, watchlist, crafting, drives), profiler, McText, shadcn ui/
│   ├── src/pages/           login, overview, server, energy (Flux list + network), ME networks (list +
│   │                        grid), storage (admin), account; routes in src/router.tsx
│   └── e2e/                 Playwright: mocked API with extreme data, layout + flow tests
└── api/openapi.yaml         HTTP contract; the TS client is generated from it
```

The mod jar contains the platform classes, the core classes and the built web UI, plus
`sqlite-jdbc` and `netty-codec-http` embedded via Forge Jar-in-Jar. Adding
a Minecraft version or loader means adding another `platform/<loader>-<version>/`
module; `core` and `web` do not change ([ADR-0002](adr/0002-module-structure.md)).

## Data flow

```mermaid
flowchart LR
    subgraph ST[Server thread]
        T[Tick hook] --> P1[Flux provider]
        T --> P2[Server provider]
        T --> P3[AE2 provider]
        T --> P4[Profiler provider]
    end
    P1 & P2 & P3 & P4 -->|immutable ProviderSnapshot| Q[(Bounded queue)]
    Q --> L[Latest-state cache]
    Q --> W[Storage writer thread]
    W --> DB[(SQLite, WAL)]
    L --> API[Netty: REST + SSE]
    DB --> API
    API -->|permission check per request and per push| UI[Web UI / PWA]
```

1. **Collect** (server thread). On each tick the collector checks which providers are
   due (`intervalTicks`). A due provider reads world and mod state and returns an
   immutable `ProviderSnapshot`. Time spent is measured against `budgetNanos`; providers
   that keep overrunning get a longer interval and are flagged in the admin UI.
2. **Hand off.** Snapshots go into a bounded queue. If it is full, the oldest entry is
   dropped and counted. The tick never blocks ([ADR-0004](adr/0004-threading-and-snapshots.md)).
3. **Latest state.** The newest snapshot per provider is kept in memory for live views
   and SSE. Inventory scans (only some snapshots carry one), the lines of running crafting
   jobs and other live tables (the profiler's top list) are kept in `InventoryState`; they are
   served on request and never pushed over SSE.
4. **Store** (single writer thread). Events are stored always; metrics only when they
   leave the deadband or the heartbeat interval passed; inventory scans as per-minute
   changes of the keys that changed. Rollup and retention jobs run
   on the same thread in batches ([ADR-0005](adr/0005-data-model.md),
   [ADR-0006](adr/0006-storage-sqlite.md)).
5. **Serve** (Netty event loop + worker pool). REST for queries and history, SSE for
   live updates. Every response and every push is filtered by the viewer's permissions
   ([ADR-0008](adr/0008-permissions.md)).

## Threads

| Thread | Does | Must not |
|---|---|---|
| Server thread | `DataProvider.collect()`, world actions (`ActionQueue`, ADR-0016), `/ov` commands | block on IO, DB or network (exception: the rare `/ov` auth commands do a few millisecond SQLite writes so their replies reach RCON) |
| Storage writer | all SQLite writes, rollups, retention | touch world state |
| Netty event loop | HTTP parsing, SSE writes | run DB queries (hand off to workers) |
| HTTP workers | DB reads, response building, queueing world actions and waiting for them | touch world state |
| Icon writer | stores icons sent by a client (`/ovicons export`) | touch world state |

A client with Overseer installed (optional) only ever renders icons on its own render thread
and sends them over the `overseer:icons` network channel; nothing else runs on clients.

## Time

Every value carries a `Timestamp`: wall clock (UTC), accumulated server uptime in
milliseconds, and the uptime session number. Charts default to **server uptime** (offline
time collapsed to a marker) and can switch to **calendar time**. See
[ADR-0005](adr/0005-data-model.md).

## World actions

Changes to the game (renaming a Flux device, ...) go through one endpoint,
`POST /api/v1/actions`. Core checks who may act (operators, or what the mod allows), hands the
action to the server thread through `ActionQueue`, waits for the result and logs it in
`action_log`. The provider's `ActionHandler` checks permissions again against live state and
applies the change. See [ADR-0016](adr/0016-world-actions.md).
An action may finish later (AE2 computes crafting plans on its own thread): the provider completes
it on the server thread, the caller waits up to 60 s, and the result may carry data such as the
plan ([ADR-0019](adr/0019-actions-that-finish-later.md)).

## Security model (summary)

Bind to `127.0.0.1` by default; expose through a reverse proxy with TLS. Login by a
one-time code confirmed in-game; session in an HttpOnly cookie; device tokens as bearer
tokens; all secrets stored hashed. Server console is always super-admin. Details:
[ADR-0007](adr/0007-authentication.md).
