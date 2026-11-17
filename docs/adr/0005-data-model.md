# 0005. Data model: events vs metrics, stable identity, two clocks

- Status: Accepted
- Date: 2026-11-17

## Context

Overseer stores history for charts ("my iron went down over the last days") and for
forecasts. Servers go offline, singleplayer worlds pause, devices get renamed, and the
database must not grow without bound.

## Decision

### Two kinds of data

| Kind | Examples | Stored |
|---|---|---|
| **Events** | device added/removed, renamed, zone changed, member joined, network created | Always, with timestamp |
| **Metrics** | FE/t in and out, stored energy, TPS, MSPT, item counts | When the value leaves the deadband, plus a heartbeat |

Metrics: a new sample is stored only if it differs from the last stored sample by more
than the metric's `relativeDeadband` (default 1 %), and in any case once per heartbeat
interval (default 60 s). Samples are collected every second for live views; storage
is thinned by these rules.

### Rollups

Raw samples are aggregated into minute and hour buckets storing **min, max, average and
last**. Default retention: raw 48 h, minute 14 days, hour 1 year. All configurable per
resolution, including "unlimited".

Implementation notes (M1b):

- Because stored samples are deadband-thinned, raw data is a **step function** (a value holds
  until the next sample). Rollups and chart buckets are **time-weighted** over it; a plain
  average of stored points would over-weight periods of change.
- Rollups run on the storage writer every minute. Watermarks in `meta` track progress: minutes
  are rolled up once complete (one minute of slack), hours once their minutes are.
- Retention deletes by server uptime and never deletes raw samples or minute rows that were
  not rolled up yet. `0` means keep forever.
- `GET /api/v1/history` chooses raw, minute or hour data from the requested number of points
  and fills the newest, not yet rolled-up part from finer data.

### Stable identity

Resources are keyed by `ResourceRef(provider, type, id)` where `id` is stable and never
derived from display names. Flux device: dimension + block position. Flux network:
Flux network id. AE2 grid: dimension + controller position (ADR-0011). Names, zones
and owners are attributes, changed through events with a validity start, so renaming
never splits history.

### Two clocks

Every record carries a `Timestamp`:

- `wall`: UTC instant (calendar time)
- `uptimeMillis`: accumulated real milliseconds while the server was ticking, persisted
  across restarts, monotonic. Counts wall time between ticks, not ticks, so lag does not
  stretch it. A gap of more than 5 s between ticks (singleplayer pause, freeze) counts
  as paused.
- `session`: uptime session number, incremented on every start.

Charts default to **server uptime** (offline periods collapsed into a marker showing the
real duration) and can switch to **calendar time** (gaps shown as such). Hover always
shows the real date.

## Consequences

- Rates like "iron per hour" are computed per hour of server uptime, matching how
  players experience the game.
- Every query API takes a time axis parameter.
- Storage stays small for stable values; noisy values are bounded by the heartbeat plus
  deadband exits.

## Alternatives considered

- **Store every sample**: simple, but 1 Hz x many devices grows fast.
- **Store only changes**: noisy power values change every second; and a constant value
  would leave no proof of being sampled.
- **Minecraft game time (ticks)** as clock: lag stretches it; Minecraft days (20 min) do
  not match the "last 24 hours" intent.
