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
of the old id stays until retention removes it.

Grids without a controller (ad-hoc grids):

- **Registered**: a player looks at one of its blocks and runs `/ov ae2 register <name>`, or
  an admin adopts a hidden grid in the UI (`POST /api/v1/registrations`). The id is that
  block's position, the player (admin) is the owner; data and access work like controller
  grids. The owner or an admin can remove the registration on the grid page. Registrations
  are looked up only in loaded chunks; chunks are never loaded for Overseer.
- **Hidden**: not registered and at least 5 nodes. Shown to admins only, id = lowest node
  position (changes with the grid), no history. Smaller grids are ignored.
- A registered grid that gets a controller is tracked by its controller from then on.

AE2 grids have no names. The name is an Overseer override (`PUT /api/v1/overrides`, key
`name`, max 48 characters); without one the UI shows "Network at x, y, z".

## Access

- The player who placed the identifying controller (AE2 records the owner of each node)
  sees the grid and may rename it.
- Members of the owner's **FTB Teams** party see it (not rename). Recomputed every second:
  leaving the party removes access within a second. Personal and server teams add nobody.
  Without FTB Teams installed this rule does nothing (`Teams.detect()`).
- **Shares**: the owner (or an admin) shares the grid with single players by name on the
  grid page (`/api/v1/shares`). Shared players view, never edit. Only players who logged in
  to the dashboard once can be found. The owner removes a share; a player can also remove
  their own. AE2 declares `Capability.SHARING`; Flux does not (Flux has its own members).
- Dashboard admins see and edit everything.

- **Access requests**: below their own networks, players see the other grids with an owner
  (Overseer name or "Network of <owner>", never positions) and can ask for access. The
  owner gets a chat message in game (right away if online, else on the next login) and
  allows or declines on the grid page; the list page shows a badge per grid. Allowing
  creates a share. After a decline the same player can ask again after 24 hours.

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

## Crafting CPUs

Each CPU is a resource `ae2/cpu/<dimension>@x,y,z` (lowest corner of the multiblock), visible
and editable exactly like its grid (shares included). CPUs of hidden grids are not reported.

| Attribute | Meaning |
|---|---|
| `grid` | Id of the grid |
| `name` | Name from an anvil, empty if none |
| `storage`, `coProcessors` | Bytes, co-processor count |
| `mode` | `any`, `player_only`, `machine_only` (AE2 selection mode) |
| `busy`, `job`, `jobKind`, `jobName`, `jobAmount` | Running job: stack key, kind, name and requested amount; empty when idle |

| Metric (while busy) | |
|---|---|
| `job_total`, `job_progress` | Items the whole job crafts (all steps) and how many are done |
| `job_elapsed` | Seconds since the job started; AE2 keeps counting across restarts |

The job history comes from the attribute events (`GET /api/v1/events?resource=…&attribute=job`).
A job that resumes after a restart has the same attributes, so it is not recorded twice. The UI
estimates the remaining time from the average pace so far.

Job lines: every second the reader also copies what each running job holds, like AE2's
crafting status screen: per key `stored` (in the CPU), `active` (sent to machines) and
`pending` (still to craft), most active first, at most 300 lines per job. They travel in
`ProviderSnapshot.jobs`, are kept in memory only and served by `GET /api/v1/jobs?resource=…`
(404 while idle). Nothing of it is stored.

## Drives and cells

Every drive and ME chest of a grid (anything implementing AE2's `IChestOrDrive`, add-on drives
included) is a resource `ae2/drive/<dimension>@x,y,z`, visible like its grid.

| Attribute | Meaning |
|---|---|
| `grid` | Id of the grid |
| `block` | Registry id, e.g. `ae2:drive`, `ae2:chest` |
| `cells` | `slot=item=state;…`; state `empty`, `not_empty`, `types_full`, `full`. Changes when a cell is inserted, removed or fills up, so these moments are events |

| Metric (per drive, and summed per grid) | |
|---|---|
| `bytes_used`, `bytes_total` | Bytes of AE2 basic cells |
| `types_used`, `types_total` | Types of AE2 basic cells |

Add-on cells that AE2 does not describe in bytes (e.g. bulk cells) show their state only. The
cell inventories come from the drive's own cache (`getOriginalCellInventory`), so reading them
costs no NBT parsing. Metrics are skipped while a grid boots.

## Contents (inventory scan)

Every 10th collection (every 10 s) the reader also copies the full cached inventory of each
identified grid: one `Stack` per key with id, kind, name and amount. Hidden grids, booting and
unpowered grids are not scanned (they report empty). The id and name of an AE2 key are built
once and cached, so a scan costs one pass over the cache plus one small object per key. The
collector page shows the cost: every 10th run of `ae2` includes the scan.

| Key | Example |
|---|---|
| `item:<registry id>` | `item:minecraft:iron_ingot` |
| `item:<registry id>#<hash>` | Same item with NBT (renamed, enchanted, damaged). Hash of the NBT, independent of tag order, stable across restarts |
| `fluid:<registry id>[#<hash>]` | `fluid:minecraft:water`, amounts in mB |
| `other:<key type>/<id>` | Add-on key types, e.g. chemicals |

Names are resolved by the server (English) and are for display only; a key keeps its history
when its name changes.

Storage (`V008__inventory.sql`, core, usable by other storage mods):

- `inventory_key`: key, kind, name.
- `inventory_amount`: current amount per grid and key; a key that is gone has no row.
- `inventory_change`: net change per grid, key and minute (`delta`, plus the amount at the end
  of the minute). A key that changes every 10 s still writes one row per minute. Only keys
  that changed are written. Kept for the raw retention (48 h by default).

The first scan of a grid is a baseline, not a change, so installing Overseer does not show the
whole inventory as gained. A restart compares against the stored amounts: things that changed
while the server was off count as one change at the first scan.

`GET /api/v1/inventory?resource=…` returns the newest scan with the net change of each key over
the last hour and day. Keys used up completely are listed with amount 0 while they have a
change. Search, kind filter, sort (amount, change, name) and paging happen on the server.
