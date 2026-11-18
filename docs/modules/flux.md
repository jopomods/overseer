# Flux Networks module

Provider id `flux`. Active when the mod `fluxnetworks` is loaded. Tested against Flux
Networks 7.2.1.15 for Forge 1.20.1.

Code: `platform/forge-1.20.1/.../integration/flux/`

| Class | Role |
|---|---|
| `FluxReader` | The only class that touches Flux objects. Server thread only. Copies into `FluxModel`. |
| `FluxModel` | Plain records: networks, statistics, members, devices. |
| `FluxMapping` | Pure: model → resources and samples; `AccessIndex` for permissions. Unit-tested. |
| `FluxProvider` | `DataProvider`: collects every 20 ticks (budget 2 ms), rebuilds the access index. |

## Data source

`FluxNetworkData.getAllNetworks()`; per network `getStatistics()`, `getAllMembers()` and
`getAllConnections()`. Connections include devices in unloaded chunks (Flux keeps them as
"phantom" devices with their last known values); they appear with `loaded=false`.

## Resources

| Resource | Attributes | Metrics |
|---|---|---|
| `flux/network/<networkId>` | `name`, `color` (`#rrggbb`), `owner` (UUID), `security` (`public`/`encrypted`/`private`), `plugs`, `points`, `storages`, `controllers` | `energy_input`, `energy_output` (FE/t), `buffer`, `energy` (FE), `tick_micros` |
| `flux/member/<networkId>:<uuid>` | `network`, `player`, `name`, `access` (`owner`/`admin`/`user`/`blocked`/`super_admin`) | — |
| `flux/device/<dimension>@x,y,z` | `network`, `type` (`plug`/`point`/`storage`/`controller`), `name`, `dimension`, `pos`, `owner`, `priority`, `limit`, `maxLimit`, `disableLimit`, `surge`, `loaded`, `forcedLoading` | `transfer` (FE/t, as reported by Flux), `buffer` (FE; stored energy for storages) |

Device identity is the block position, never the name: renaming a device or moving it to
another network keeps its history (the `network` and `name` attributes change as events).

Membership changes, renames and devices added or removed are recorded as events, giving
a history of the network for free.

## Access (ADR-0008)

Mirrors Flux's own rule, evaluated on the last collected state:

- members use their access level; `blocked` sees nothing;
- non-members see **public** networks (Flux treats them as `user`), nothing else;
- devices and members are visible exactly when their network is;
- dashboard admins and operators see everything (core).

Changes apply within one collection interval (1 s).

## UI

- `/energy`: one card per visible network (color, security, input/output, stored energy,
  device count).
- `/energy/<networkId>`: charts of input/output and stored energy, totals and balance (input − output), zones, devices (filter by type,
  search by name, zone, position, dimension; unloaded devices dimmed), members with access.
- Flux reports `transfer` positive for plugs (energy taken in) and negative for points (energy
  delivered), confirmed with real data. The UI shows magnitudes for plugs and points;
  storages show the signed value plus stored energy.

## Energy flow

The network page shows where energy comes from and where it goes: plug zones and
discharging storages → network → point zones and charging storages. Storages use the same
sign as plugs and points (positive = adding energy to the network = discharging); this is an
assumption until verified with real storage data.

Two detail levels, switchable above the chart and remembered per viewer:

- **Zones** (default): zone → network → zone.
- **Devices**: device → zone → network → zone → device. Devices on the same side with the
  same zone and the same name are grouped (`Diesel ×2`). Per zone the seven largest groups
  are shown and the rest is one `+N more` node, so huge networks stay readable. Storages
  connect straight to the network.

On phones the list shows each zone with its devices indented below.

## Zones over time

A mirrored stacked area chart shows generation (plugs, up) and consumption (points, down)
per zone over time. It sums the `transfer` history of every device by its **current** zone
in one request (`/api/v1/history` accepts up to 250 resources). The six largest zones get
their own color; the rest is grouped as "other".

## Zones

The zone of a device is, in order: the zone set in Overseer (`PUT /api/v1/overrides`,
key `zone`), else the part of its name before the first `:` (`Power plant: Diesel 1` →
`Power plant`), else none. Overrides are keyed by the device position, so they survive
renames. Network owners and admins (Flux access levels) and dashboard admins may edit;
the UI shows a pencil per device for them and marks overridden zones with `*`.

## Forecast

The network page shows "empty in ~X" for stored energy, from a least-squares slope over
the last hour of history (`web/src/lib/forecast.ts`); changes below 1 %/h count as stable.
"Full in" needs the storage capacity, which Flux does not expose yet.

## Tests

- `FluxMappingTest` (JUnit): mapping and access rules.
- `FluxGameTests` (Forge GameTest, also in CI): creates a real network, places and
  connects a plug, a point and a storage, collects, and checks resources and access.

## Storage capacity and fill time

Flux storages report their capacity (`capacity` attribute, from the storage's own handler). A
storage in an unloaded chunk is a "phantom" and cannot be asked; Overseer remembers the capacity
it last saw while the server runs. When every storage's capacity is known, the network page shows
how full the storages are (bar and percent) and, while charging, "full in ~X"; while emptying,
"empty in ~X" (least-squares slope over the last hour of stored energy).
