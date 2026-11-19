# 0020. Flow: what devices move into and out of a storage network

- Status: Accepted
- Date: 2026-11-19

## Context

Players want to see where items enter and leave their ME network: which import bus brings in
ore, which export bus feeds a machine, what players take from terminals, what crafting uses. The
inventory scans (ADR-0011) only show the net change of each item per minute, not who caused it.
AE2 keeps no counters per device. Every insert into and extract from a network's storage, however,
goes through one place, the grid's `NetworkStorage`, and carries an `IActionSource`: the machine
(bus, interface, crafting CPU, ...) or the player that asked.

## Decision

**Measure at the network storage.** An optional AE2 mixin (rules of ADR-0018) on
`NetworkStorage#insert` and `#extract` reports every applied (`MODULATE`, amount > 0) operation on
the server thread to a recorder: network, source, key, amount. Simulations are ignored; so are
calls from other threads (AE2 plans crafting on its own thread, and only simulates there). The
recorder only adds numbers to a map; the provider turns sources into devices once per collection.

**Devices.** A source is named by position (`dimension@x,y,z`, plus `/<side>` for parts), or
`player:<uuid>`, or `other` when it cannot be placed. Each has a kind (`import_bus`, `export_bus`,
`interface`, `pattern_provider`, `crafting`, `player`, ... or the part's item path) and a display
name. Items moved between cells inside the network never pass through and are not counted.

**Core model.** `ProviderSnapshot.flows`: per network, what each device inserted and extracted of
each key since the last collection. Generic, so other storage mods can report flows too.

**Storage.** `flow_device` (network, device, kind, name) and `flow_minute` (device, minute, key,
inserted, extracted), summed with an upsert per collection. Kept for the raw retention (48 h by
default) like inventory changes; purged with the provider. Keys are shared with inventories.

**API.** `GET /api/v1/flows` (totals per device and key in a window, largest first, at most 5000
rows) and `GET /api/v1/flows/history` (per minute, for one item, one device or all). Visible to
whoever sees the network.

**Server setting** `ae2.flow` (default on): off stops recording at once; the mixin then returns
after one boolean check.

## Consequences

- The insert/extract path is AE2's hottest; the mixin adds a field read, a thread check and a map
  update per applied operation. The collector page shows the provider's cost; the setting turns it
  off.
- Big bases produce many rows: one per device, key and minute with flow. Raw retention bounds it.
- Flows show what passed the network's storage, not what a machine did with it: an export bus
  that fails to insert is not counted, because nothing was extracted.

## Alternatives considered

- **Diffing bus inventories or inventory scans:** cannot tell devices apart, and misses anything
  faster than a scan.
- **Mixins on each device type (buses, interfaces, providers):** many targets, and add-on devices
  would be missing. The storage is the one place everything passes.
- **Live only (no database):** no history across a session, which is what players ask for.
