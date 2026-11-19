# Applied Energistics 2

Provider id `ae2`. Code: `platform/forge-1.20.1/.../integration/ae2/` (`Ae2Reader` touches
AE2, `Ae2Mapping` is pure and unit-tested, `Ae2Provider` glues them; `Ae2Writer` changes AE2,
see World actions). GameTests: `Ae2GameTests`, `Ae2ActionGameTests`. Decisions:
[ADR-0011](../adr/0011-ae2-discovery-and-access.md), [ADR-0017](../adr/0017-ae2-actions.md),
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
- **Placer access** (server setting `access.ae2PlacerAccess`, default off): every player who
  placed any block or part of a grid views it (never edits). AE2 records the placer per node;
  Overseer walks the nodes with each inventory scan (every 10 s), so it applies within 10 s.
  Off by default because a player could place one cable on a neighbour's network to watch it.

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
| `no_channel` | count | devices that need a channel and have none; from the inventory scan (every 10 s), not while booting |

Cost: AE2 keeps the network inventory cached; the reader only iterates it to count, about
one pass over the item types per grid and second. Measure with the collector page before
raising the interval.

## Power

The grid page shows stored power as a bar and "full in ~X" / "empty in ~X" from AE2's average
injection minus usage (AE per tick).

## Crafting CPUs

Each CPU is a resource `ae2/cpu/<dimension>@x,y,z` (lowest corner of the multiblock), visible
and editable exactly like its grid (shares included). CPUs of hidden grids are not reported.

| Attribute | Meaning |
|---|---|
| `grid` | Id of the grid |
| `name` | Name from an anvil or the dashboard, empty if none |
| `storage`, `coProcessors` | Bytes, co-processor count |
| `mode` | `any`, `player_only`, `machine_only` (AE2 selection mode) |
| `busy`, `job`, `jobKind`, `jobName`, `jobAmount` | Running job: stack key, kind, name and requested amount; empty when idle |
| `suspended` | The running job is suspended (AE2 builds with job suspension only; always `false` otherwise) |
| `stalledSince` | When the running job last made progress, if AE2's progress has not moved for 5 minutes (and the job is not suspended); empty otherwise. Tracked in memory, so a restart starts the clock again |

| Metric (while busy) | |
|---|---|
| `job_total`, `job_progress` | AE2's scale (2,147,483,647) and the done share of it; only the ratio is meaningful |
| `job_elapsed` | Seconds since the job started; AE2 keeps counting across restarts |

The grid page shows named CPUs and CPUs reserved for players ("Players only") first; the others,
which mostly serve automation, fold away behind "N more CPUs (automation) · M busy". Without any
named or player CPU, all are shown.

The job history comes from the attribute events (`GET /api/v1/events?resource=…&attribute=job`).
A job that resumes after a restart has the same attributes, so it is not recorded twice. The UI
estimates the remaining time from the average pace so far.

Job lines: every second the reader also copies what each running job holds, like AE2's
crafting status screen: per key `stored` (in the CPU), `active` (sent to machines) and
`pending` (still to craft), most active first, at most 300 lines per job. They travel in
`ProviderSnapshot.jobs`, are kept in memory only and served by `GET /api/v1/jobs?resource=…`
(404 while idle). Nothing of it is stored.

## Names

Item, fluid and CPU names come as the server resolves them, including legacy colour codes
(`§5EV Energy Hatch`): modpacks colour items by voltage tier this way. The UI shows them in
colour by default; the account page has "Colour codes in names: Coloured / Plain". Search and
name sorting ignore the codes. AE2 numbers unnamed CPUs by their position in the grid's CPU list
(named ones count too); the attribute `number` carries it, so the dashboard says "CPU #8" like
the game.

## Infinity cells

Add-on infinity cells report the largest 32-bit number (2,147,483,647 items, or as many buckets
for fluids). The UI shows such amounts as ∞.

Job progress is not affected: AE2 15 always reports a job's total as 2,147,483,647 and its progress
as a share of it (`ElapsedTimeTracker`, weighted across item and fluid work). The card shows the
percentage and the remaining time from it, never the scaled counts. AE2's own crafting status
title shows the same estimate (remaining time, not elapsed), and it survives restarts.

## Icons

Icons are keyed by stack key (`GET /api/v1/icon?key=…`): exported exact key, then the base key
without NBT hash, then the item's texture from its mod jar (`assets/<ns>/textures/item|block/<path>.png`).
`/ovicons export` (client with Overseer, operators) renders every item and source fluid at 32×32 off
screen and sends the PNGs in batches below 32 KiB over the optional `overseer:icons` channel; the
server stores them off the server thread (`icon` table, V010). Clients and servers without the
channel still connect.

## Watchlist and deficits

Owners star items in the contents list (at most 50 per grid, table `watch`, `V009`). Watched
items are read every second straight from AE2's cached inventory (one lookup per item) and
recorded as metrics `stock:<stack key>` of the grid, so they get history, rollups and live SSE
updates like any metric. A watched item can have a target (minimum stock).

The forecast is linear: the net change of the last day (else the last hour) from the inventory
scans, as a rate per hour. It shows "empty in ~X" while an item shrinks and "target in ~X" while
it is below target and grows; nothing beyond two weeks. The watchlist sorts deficits first
(largest share missing), then items that run out soonest. The contents list shows "empty in ~X"
for any shrinking item.

`GET/POST/DELETE /api/v1/watches`: viewers read, owners (edit rights) change.

**Auto-crafting (ADR-0021).** A watched item with a target can be crafted up to it automatically
(`restock: true` on `POST /api/v1/watches`). Switching it on needs the right `ae2.restock` on the
grid (owner, party, operators; offered while `actions.ae2Crafting` and `actions.ae2Restock` are
on); jobs then run as that player, and only while they may still act on the grid. Every 5 s
`Ae2Restocker` checks each such item: at or above target `ok`; a job for it runs (any job)
`crafting`; no pattern `no_pattern`; otherwise it plans the difference and starts it on a CPU AE2
chooses (`planning`, then `crafting`). Missing ingredients (`missing`), no free or suitable CPU
(`no_cpu`) and failures (`unavailable`) wait a minute; a player who may no longer act gets
`denied`. The state goes into the grid's live table as rows `row=restock` (`key`, `state`, `since`),
in memory only. Removing the target switches auto-crafting off.

In the UI, a watched item with a target shows "Auto-craft to target" to those who may switch it on
(and to everyone who may edit the watchlist once it is on, to switch it off), with the state in
words; problems are red. "Craft the missing amount" is hidden while auto-crafting is on.

## Drives and cells

Every drive and ME chest of a grid (anything implementing AE2's `IChestOrDrive`, add-on drives
included) is a resource `ae2/drive/<dimension>@x,y,z`, visible like its grid.

| Attribute | Meaning |
|---|---|
| `grid` | Id of the grid |
| `block` | Registry id, e.g. `ae2:drive`, `ae2:chest` |
| `priority` | Storage priority; missing for add-on drives without one |
| `cells` | `slot=item=state;…`; state `empty`, `not_empty`, `types_full`, `full`. Changes when a cell is inserted, removed or fills up, so these moments are events |

| Metric (per drive, and summed per grid) | |
|---|---|
| `bytes_used`, `bytes_total` | Bytes of AE2 basic cells |
| `types_used`, `types_total` | Types of AE2 basic cells |

Add-on cells that AE2 does not describe in bytes (e.g. bulk cells) show their state only. The
cell inventories come from the drive's own cache (`getOriginalCellInventory`), so reading them
costs no NBT parsing. Metrics are skipped while a grid boots.

## Import and export buses

Every import and export bus (anything extending AE2's `IOBusPart`, so add-on buses too) is a
resource `ae2/bus/<dimension>@x,y,z/<side>`, visible like its grid.

| Attribute | Meaning |
|---|---|
| `grid` | Id of the grid |
| `block` | Registry id of the part item, e.g. `ae2:export_bus` |
| `kind` | `import`, `export`, or the class name of an add-on bus |
| `name` | Custom name, empty if none |
| `target`, `targetBlock` | Name (custom name if it has one) and registry id of the block the bus faces; empty when nothing or not loaded |
| `filter` | Configured keys, JSON list of `{key, kind, name}`; empty means everything for import buses and nothing for export buses |
| `upgrades` | Installed cards, `<item id>=<count>;...` sorted |
| `redstone` | `ignore`, `low_signal`, `high_signal`, `signal_pulse` |
| `craftOnly`, `scheduling` | Export buses: export only crafted items; `default`, `roundrobin`, `random`. Empty for others |
| `requested` | Crafting jobs the bus requested that still run (crafting card) |
| `active` | Power and channel |

The grid page lists them with a filter for import or export and a search over names, machines and
filter items; those who may act edit them in place (`ae2.bus.edit`), adding filter items by searching
the network's contents.

## Flow

What every device moves into and out of a network (ADR-0020). An optional mixin
(`NetworkStorageMixin`, ADR-0018) reports each applied insert and extract of a grid's
`NetworkStorage` to `FlowRecorder`, with AE2's action source; simulations and calls from other
threads are ignored. Each collection the provider turns the sources into devices and sends them as
`ProviderSnapshot.flows` of the grid; core stores them per minute (`flow_device`, `flow_minute`, raw
retention) and serves `GET /api/v1/flows` and `/api/v1/flows/history`.

| Device | Id | Kind |
|---|---|---|
| Part (bus, interface, provider, terminal, ...) | `dimension@x,y,z/<side>` | `import_bus`, `export_bus`, `storage_bus`, `interface`, `pattern_provider`, `terminal`, else the item path |
| Block (interface, provider, ME chest, ...) | `dimension@x,y,z` | `interface`, `pattern_provider`, else the block path |
| Crafting CPU | its id (lowest corner) | `crafting`: ingredients taken at the start, results put back |
| Player (terminals, wireless terminals) | `player:<uuid>` | `player` |
| Anything AE2 does not attribute | `other` | `other` |

The grid page shows the flow of a window (15 min to 48 h): **by item** (what came in and went out,
and on opening it a Sankey of the devices on both sides, a list on phones, and a chart per minute),
**by device** (what each device moved, with a chart per minute), and the buses that moved nothing in
the window.

Server setting `ae2.flow` (default on) switches recording off at once. Hidden grids report no flows.
Moving items between cells inside a network does not pass the storage and is not counted.

## Diagnosis

**Stuck jobs.** A CPU whose job has made no progress for 5 minutes gets `stalledSince` (see Crafting
CPUs). The grid page warns on the card ("No progress for 12 min"), counts stuck CPUs in the section
header, and never folds a stuck CPU away with the automation CPUs. Typical causes: a locked pattern
provider (its `locked` attribute), a missing or full machine, missing inputs; the job lines show
what the job waits for.

**Devices without channel.** With each inventory scan the reader walks the grid's nodes and lists
those that need a channel (`GridFlags.REQUIRE_CHANNEL`) and have none, at most 500: rows of the
grid's live table with `row=offline`, `id` (`dimension@x,y,z`, plus `/<side>` for parts), `item`
(what AE2 shows for the device) and `name`; the count is the metric `no_channel`. Booting grids are
not checked (channels are handed out after booting); the last answer stays until the next scan.
The grid page lists them near the top when there are any.

## P2P tunnels

Read with the inventory scan (every 10 s) and sent with every snapshot as rows of the grid's live
table, at most 1,000 per grid: `row=p2p`, `id` (`<dimension>@x,y,z/<side>`), `item` (the tunnel's
item, which names its type: ME, item, fluid, FE, redstone, light, add-on tunnels), `name` (custom
name, empty if none), `frequency` (unsigned; `0` = not linked), `hex` (as AE2 shows it), `colors`
(AE2's four colours of the frequency, `rrggbb`, comma separated), `output`, `target` and
`targetBlock` (the block it faces), `active` (power and channel). Tunnels of one type and frequency
form a link: one input, any number of outputs. ME tunnels also have a node in the network they
carry; they are listed only in the network they were placed in.

## Pattern providers

Every pattern provider of a grid, block or part on a cable (anything implementing AE2's
`PatternProviderLogicHost`, so ExtendedAE's providers too), is a resource
`ae2/provider/<dimension>@x,y,z` (blocks) or `ae2/provider/<dimension>@x,y,z/<side>` (parts),
visible like its grid.

| Attribute | Meaning |
|---|---|
| `grid` | Id of the grid |
| `block` | Registry id of the block or part item, e.g. `ae2:pattern_provider`, `ae2:cable_pattern_provider` |
| `name` | Custom name (anvil or dashboard), empty if none |
| `group`, `groupIcon` | Name and icon (stack key) AE2's Pattern Access Terminal shows: the custom name, else the machine next to it; the icon chosen in the dashboard, else AE2's |
| `icon` | Item id chosen from the dashboard, empty if none |
| `priority` | Provider priority |
| `blocking` | Blocking mode |
| `lock` | Lock crafting mode: `none`, `lock_until_pulse`, `lock_while_high`, `lock_while_low`, `lock_until_result` |
| `locked` | Why the provider waits right now (same values), `none` when it does not. Changes are events |
| `visible` | Listed in the Pattern Access Terminal |
| `patterns` | Number of patterns |

**Patterns** are read with the inventory scan (every 10 s) and sent as the grid's live table
(`GET /api/v1/details?resource=ae2/grid/<id>`), one row per pattern: `row=pattern`, `provider`, `output`,
`outputKind`, `outputName`, `amount` (per craft) and `inputs` (JSON list of `{key, kind, name,
amount}`, the first possible input of each slot). Read only; nothing is stored.

The grid page lists the providers by name with icon, voltage tier (the tier is read from the end
of the name, like Flux names, or from its start, as GregTech names machines: "HV Assembler"),
filter by tier, search over names and outputs, lock state, and each provider's patterns.

**Icons in game.** AE2 has no setting for a provider's icon in its terminal. The icon chosen in
the dashboard is stored in Forge's persistent data of the block entity (the cable bus for parts,
one entry per side), and an optional mixin (`PatternProviderLogicMixin`, ADR-0018) puts it into the
group AE2 sends to the terminal. Players need nothing on their client; an open terminal updates at
once. Without the mixin (another AE2 build) the icon shows in the dashboard only.

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

Every key has a history: clicking a row in the contents list shows a step chart (15 min to 48 h)
from `inventory_change` (`GET /api/v1/inventory/history`): the amount at the window start is the
current amount minus every change since, then one step per minute that changed. Watched items
additionally have per-second `stock:` metrics.

`GET /api/v1/inventory?resource=…` returns the newest scan with the net change of each key over
the last hour and day. Keys used up completely are listed with amount 0 while they have a
change. Search, kind filter, sort (amount, change, name) and paging happen on the server.

## World actions (ADR-0016, ADR-0017)

Grids can be operated from the dashboard through `POST /api/v1/actions`. Every action targets the
grid (`resource: ae2/grid/<id>`); the part it changes is a parameter. Allowed for the grid's owner,
the members of the owner's FTB Teams party and server operators. Shares and placer access only
view; hidden grids have no actions. `Ae2Actions` parses and checks the parameters (pure,
unit-tested); `Ae2Writer`, the only class that changes AE2 state, applies them on the server thread
and checks again against the live grid that the id still names it, the part belongs to it and the
caller may act. Parts in unloaded chunks answer `unavailable`; chunks are never loaded.

| Type | Parameters | Notes |
|---|---|---|
| `ae2.cpu.cancel` | `cpu` | AE2's `cancelJob`, as the cancel button in the crafting status: what the job holds goes back into the network. |
| `ae2.cpu.suspend` | `cpu`, `suspended` | Suspends or resumes the job. Only offered on AE2 builds that can (the Star Technology fork `15.5.4-uelm`; not AE2 15.4.12): `CraftingCpuLogic#setJobSuspended`, looked up once by reflection (`Ae2Compat`). A suspended job sends nothing to machines but still takes results back; the state is saved with the CPU. |
| `ae2.cpu.edit` | `cpu`, `name?`, `mode?` (`any`, `player_only`, `machine_only`) | Name like an anvil: at most 50 characters, colour codes count, empty removes it. AE2 joins the custom names of all blocks of a CPU, so the name goes on one block and the others lose theirs. Names are AE2's private `customName` field, set by reflection; the mode goes through AE2's config manager. |
| `ae2.drive.edit` | `drive`, `priority` | Any 32-bit integer, through AE2's `IPriorityHost` (drives, ME chests, add-on drives that have a priority). |
| `ae2.provider.edit` | `provider`, `name?`, `icon?` (item id, empty removes it), `priority?`, `blocking?`, `lock?`, `visible?` | Name as for CPUs (also the group in the Pattern Access Terminal); settings through AE2's config manager, which saves the provider like its buttons do. Unknown items are refused. |
| `ae2.bus.edit` | `bus`, `name?`, `filter?` (list of stack keys, at most 9; empty clears it), `redstone?` (`ignore`, `low_signal`, `high_signal`, `signal_pulse`), `craftOnly?`, `scheduling?` (`default`, `roundrobin`, `random`) | Import and export buses. The filter replaces the whole configuration; slots locked behind capacity cards in game accept entries that then do nothing. Craft only and scheduling exist on export buses only. Settings through AE2's config manager; the redstone mode matters only with a redstone card. |
| `ae2.craft.plan` | `key` (stack key), `amount` (1 to 10^12) | Plans a job like AE2's craft confirm screen and answers with the plan (below). AE2 computes it on its own thread; the answer comes with the next collection, at most a second later (ADR-0019). Refused when no pattern makes the key. Server setting `actions.ae2Crafting` (default on). |
| `ae2.craft.start` | `plan` (id from the plan), `cpu?` | Starts a kept plan, on the given CPU or where AE2 chooses (`submitJob`). Plans are kept 5 minutes, at most 64 on the server, and start once; only the player who planned (or an operator) may start one. Refused while items are missing, with AE2's reason when it refuses (no free CPU, too small, busy, offline). The result goes into the network. |
| `ae2.restock` | – | Not run here: the right to switch on auto-crafting for a watched item (above). `POST /api/v1/actions` refuses it. |

**Plans** (`data` of the `ae2.craft.plan` answer): `plan` (id), `output` (`{key, kind, name,
amount}`), `bytes`, `missing` (items are missing: the plan cannot start), `multiplePaths`,
`expiresInSeconds`, `used`, `missingItems`, `crafted` (lists of stacks, most first, at most 300
each) and `cpus` (`id`, `number`, `name`, `storage`, `coProcessors`, `busy`, `mode`, `fits`).
Jobs are requested as the player: online, or a stand-in with their profile while offline (Forge's
fake player), so CPUs reserved for players take them. Keys come from scans and patterns; plain items
and fluids also by registry id.

**Crafting in the UI.** With `ae2.craft.plan` allowed, the grid page has a "Craft" section above
the CPUs: search the craftable items (the outputs of the grid's patterns) or start from a hammer
button in the contents list, a pattern, or "Craft the missing amount" on a watched item below its
target (pre-filled with the deficit). The panel takes the amount (buckets for fluids), shows the
plan (bytes, missing, used and crafted items), offers the CPUs that fit and are free, and starts
the job.

CPU ids are `<dimension>@x,y,z` of the lowest corner, or `<grid id>#<index>` for CPUs without
blocks (add-ons; cancel only). Changes name the CPU or drive, so its log shows them. The grid page
shows the controls in the expanded CPU card and on each drive, and the grid's change log.

## Tests

- `Ae2MappingTest`, `Ae2ActionsTest` (JUnit): mapping, access rules, action parameters.
- `Ae2GameTests` (Forge GameTest, also in CI): discovery, hidden and registered grids, inventory
  scan, CPUs, drives, placer access.
- `Ae2ActionGameTests`: flows (an import bus inserts, a player extracts, simulations and the
  setting); devices without channel (ten drives on a grid without controller);
  remote crafting (a plan comes back with the next collection, missing
  items block the start, a good plan starts once on the chosen CPU, strangers and unknown items are
  refused, the server setting hides it); pattern providers (a block and a part on a cable) are read with their
  patterns, every setting is changed through the action, the icon shows in AE2's terminal group
  (mixin) and goes away again; a real job (processing pattern on a provider without a machine, so it never
  finishes) is renamed, switched to players only, cancelled; strangers are refused, operators may
  act; suspension is offered exactly where AE2 has it; drive priority, drives of other grids.
