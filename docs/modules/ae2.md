# Applied Energistics 2

Provider id `ae2`. Code: `platform/forge-1.20.1/.../integration/ae2/` (`Ae2Reader` touches
AE2, `Ae2Mapping` is pure and unit-tested, `Ae2Provider` glues them). GameTest:
`Ae2GameTests`. Decisions: [ADR-0011](../adr/0011-ae2-discovery-and-access.md),
[ADR-0012](../adr/0012-item-icons.md).

## Version

Compiled against AE2 **15.4.12** (modmaven) for Minecraft 1.20.1. The Star Technology
modpack ships a fork (`15.5.4-uelm`); the classes Overseer uses exist with identical
signatures in both. Dev runs also load GuideME (required by AE2 15.4.12).

Besides the public API (`appeng.api.*`) the reader uses two internals, both stable across
AE2 15: `appeng.hooks.ticking.TickHandler#getGridList()` (the only list of all grids) and
`appeng.blockentity.networking.ControllerBlockEntity` (to find controllers). If a future
AE2 changes them, the integration fails to load and is skipped (`Integrations`).

## Discovery and identity

Every second the reader walks all grids and keeps those with at least one controller.
Identity is the controller with the lowest position (dimension, then x, y, z):
`ae2/grid/<dimension>@x,y,z`. Removing exactly that controller changes the id; the history
of the old id stays until retention removes it. Grids without a controller (ad-hoc grids)
are skipped for now; adopting them follows (see roadmap).

AE2 grids have no names. The name is an Overseer override (`PUT /api/v1/overrides`, key
`name`, max 48 characters); without one the UI shows "Network at x, y, z".

## Access

For now: the player who placed the identifying controller (AE2 records the owner of each
node) sees the grid and may rename it; dashboard admins see and edit everything. FTB team
members, explicit shares and access requests follow (ADR-0011).

## Data

| Attribute | Meaning |
|---|---|
| `owner`, `ownerName` | Owner UUID and last known name (server profile cache) |
| `dimension`, `pos` | Identifying controller |
| `state` | `controller_online`, `controller_conflict` (AE2 `ControllerState`) |
| `powered`, `booting` | Grid has power / is still booting |
| `nodes`, `controllers` | Node and controller counts |
| `cpus`, `cpuStorage`, `coProcessors` | Crafting CPUs, their total storage (bytes) and co-processors |
| `idlePower` | Idle usage in AE/t |
| `otherTypes` | Inventory keys that are neither items nor fluids (add-ons) |

| Metric | Unit | |
|---|---|---|
| `power_stored`, `power_max` | AE | 1 AE = 2 FE; shown in AE like AE2 does |
| `power_usage`, `power_injection` | AE/t | AE2's own averages |
| `items`, `item_types` | count | from AE2's cached network inventory; not sampled while the grid boots or has no power (it reports empty then) |
| `fluids`, `fluid_types` | mB, count | UI shows buckets |
| `channels` | count | channels in use |
| `cpus_busy` | count | crafting CPUs with a job |

Cost: AE2 keeps the network inventory cached; the reader only iterates it to count, about
one pass over the item types per grid and second. Measure with the collector page before
raising the interval.
