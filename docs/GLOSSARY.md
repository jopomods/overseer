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
| **ME network** (*ME-Netzwerk*), **grid** | An AE2 network. In code and API `grid` (AE2's term), in the UI "ME network". Identified by its lowest controller (ADR-0011). |
| **Inventory scan** (*Inventar-Scan*) | A full copy of a resource's contents (AE2: every 10 s), one `Stack` per key. Stored as per-minute changes; the first scan of a resource is a baseline. |
| **Stack key** | Stable id of a thing in an inventory: `item:<id>`, `fluid:<id>`, plus `#<hash>` of its NBT. Names are display only. |
| **Crafting CPU** | AE2 multiblock that runs one crafting job at a time. Resource `ae2/cpu/<dimension>@x,y,z` (lowest corner); visible like its ME network. |
| **Drive** (*Laufwerk*) | AE2 drive or ME chest holding storage cells. Resource `ae2/drive/<dimension>@x,y,z`, visible like its ME network. |
| **Watchlist** | Items of an ME network read every second (`stock:<key>` metrics), optionally with a target (minimum stock). Below target = deficit (*Fehlmenge*). |
| **Auto-crafting** (*Automatisch nachcraften*) | A watched item with a target the network crafts up to it on its own, as the player who switched it on (ADR-0021). |
| **Job** (*Auftrag*) | What a crafting CPU crafts: output, amount, progress. Starts and ends are events of the CPU. |
| **Bus** (*Bus*) | AE2 import or export bus: a cable part that pulls items from or pushes them into the block it faces. Resource `ae2/bus/<dimension>@x,y,z/<side>`. |
| **Pattern provider** | AE2 block or cable part that holds patterns and sends their inputs to the machine next to it. Resource `ae2/provider/<dimension>@x,y,z[/<side>]`; its patterns are rows of the grid's live table. |
| **Suspend** (*Pausieren*) | Halting a crafting job without cancelling it; only AE2 builds that have it (e.g. the Star Technology fork). |
| **Share** (*Freigabe*) | An owner lets one more player view a resource in Overseer. Only for providers with `SHARING` (AE2); never grants editing. |
| **Zone** (*Bereich*) | Grouping of Flux devices. Taken from the device name prefix before the first `:` (`Power plant: Diesel 1` is zone `Power plant`), overridable in the UI. |
| **Link code** | One-time, short-lived code shown on the login page and confirmed in-game with `/ov link <code>`. |
| **Device token** | Long-lived read-only bearer token for kiosks and API clients; revocable. |
| **Dashboard admin** | Player granted admin by an op. Sees everything. Ops and the server console are always admins. |
| **Recording** | Snapshots over time in a versioned JSON Lines file. Committed ones are anonymized. See docs/guides/recordings.md. |
| **Simulator** | Overseer without Minecraft, replaying recordings as live providers. |
| **Voltage tier** (*Spannungs-Tier*) | GregTech's power levels (LV, MV, HV, ...). Optional: the last word of a Flux device name, coloured and filterable when the server uses tiers (`dashboard.energyTiers`). |
| **World action** (*Änderung*) | A change to the game made from the dashboard, e.g. renaming a Flux device. Run on the server thread, logged with old and new values (ADR-0016). |
| **Action log** (*Änderungsprotokoll*) | Who changed what and when, refusals included. Visible to everyone who can see the resource. |
| **Collector cost** | Time a provider spends in `collect()` per run; shown in the admin UI. |
