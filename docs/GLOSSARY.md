# Glossary

| Term | Meaning |
|---|---|
| **Provider** | An integration implementing `DataProvider`: `server`, `flux`, `ae2`, ... One per data source. |
| **Platform** | A loader/version-specific module (`platform/forge-1.20.1`) that hosts providers and wires Overseer into the game. |
| **Snapshot** | Immutable `ProviderSnapshot` produced by one `collect()` call: resources, their attributes and metric samples at one `Timestamp`. |
| **Resource** | Something a provider observes, identified by a stable `ResourceRef` (`provider/type/id`), e.g. a Flux network or device. |
| **Attribute** | Mutable descriptive data of a resource (name, owner, zone). Changes are recorded as events; never part of the identity. |
| **Metric** | A named numeric series of a resource, e.g. `energy_input`. Kind is `GAUGE` (a level) or `RATE` (a flow per tick). |
| **Sample** | One metric value at one timestamp. |
| **Event** | A discrete change: resource added/removed, renamed, member joined. Always stored. |
| **Deadband** | Relative change threshold below which a new sample is not stored. |
| **Heartbeat** | A sample stored regardless of the deadband after a fixed interval, so series have no gaps. |
| **Rollup** | Aggregation of older samples into coarser buckets (minute, hour) with min, max, average and last value. |
| **Retention** | How long each resolution (raw, minute, hour) is kept. |
| **Server uptime** (*Serverlaufzeit*) | Accumulated real time during which the server was ticking. Offline time and singleplayer pauses do not count. Default chart axis. |
| **Calendar time** (*Kalenderzeit*) | Wall-clock time including offline periods. |
| **Uptime session** | One continuous run of the server, from start to stop. Numbered from 1. |
| **Zone** (*Bereich*) | Grouping of Flux devices. Taken from the device name prefix before the first `:` (`Power plant: Diesel 1` is zone `Power plant`), overridable in the UI. |
| **Link code** | One-time, short-lived code shown on the login page and confirmed in-game with `/ov link <code>`. |
| **Device token** | Long-lived read-only bearer token for kiosks and API clients; revocable. |
| **Dashboard admin** | Player granted admin by an op. Sees everything. Ops and the server console are always admins. |
| **Recording** | Snapshots over time in a versioned JSON Lines file. Committed ones are anonymized. See docs/guides/recordings.md. |
| **Simulator** | Overseer without Minecraft, replaying recordings as live providers. |
| **Collector cost** | Time a provider spends in `collect()` per run; shown in the admin UI. |
