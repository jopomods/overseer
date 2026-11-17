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
