# 0014. Tick profiler with Forge's timers and one mixin

- Status: Accepted
- Date: 2026-11-18

## Context

Admins want to know what costs tick time on the server: which machines, block types or entities.
Minecraft's own profiler (`/debug`) measures sections, not objects. Forge has per-object timers
(`TimeTracker.BLOCK_ENTITY_UPDATE` and `ENTITY_UPDATE`, behind `/forge track`). They record the
last 100 ticks of every ticking object while enabled and cost nothing while off.

In Forge 1.20.1 the block entity timer is broken: `LevelChunk$BoundTickingBlockEntity.tick`
calls `trackStart` but never `trackEnd`, so no block entity is ever recorded. Entities work.

## Decision

- A `profiler` provider (platform, always present) reads both timers. Admins switch it on from
  the dashboard: for a duration (10 s to 24 h) or continuously, measuring every 5 to 300 s.
  The setting lives in `meta` (`ProfilerStore`); a timed run ends on time, a continuous one
  survives restarts.
- Each measurement sums the average time per tick by block or entity type (largest 15 types,
  the rest as "other") into metrics `tick_us` and `objects` of resources `profiler/type/<kind>:<id>`
  and `profiler/total/all`. History and the stacked chart come from the normal pipeline. After a
  measurement the timers restart, so each one covers its own interval.
- Each measurement also sums every type (not only the largest 15) by mod, the namespace of its
  registry id: resources `profiler/mod/<namespace>` with the mod's display name. The chart switches
  between "by type" and "by mod".
- The top 50 single objects (type, name, position, time) are a live table
  (`ProviderSnapshot.details`, `/api/v1/details`): never stored, they change with every measurement.
- One mixin (`BoundTickingBlockEntityMixin`) completes Forge's timer: it remembers the object
  passed to `trackStart` (`@ModifyArg` on the Forge call, no Minecraft field named) and calls
  `trackEnd` at the end of `tick`. It also makes `/forge track start te` work again.
- Admins only: the provider's access policy denies everyone; core lets admins through.

## Consequences

- Overseer now ships a mixin (Mixin 0.8.5 via ModDevGradle, refmap in the jar). Any mod that
  replaces this method entirely would conflict; `@ModifyArg` and a tail injection keep it small.
- While measuring, every ticking object costs two `System.nanoTime` calls and a map update.
  On large bases that is measurable, so the UI says so and timed runs are the default.
- The last result stays visible after the profiler stops, until the next start or a restart.

## Alternatives considered

- **Wrapping the block entity tickers by reflection**: needs SRG field names at runtime and
  re-wrapping whenever a chunk rebinds a ticker; more fragile than one injection.
- **Spark or another profiler mod**: not on every server, and its data does not reach the
  dashboard.
- **Sampling stack traces**: tells where time goes in code, not which machine at which position.
