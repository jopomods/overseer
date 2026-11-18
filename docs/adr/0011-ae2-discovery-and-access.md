# 0011. AE2 grid discovery and access

- Status: Accepted (implementation in M2)
- Date: 2026-11-17

## Context

AE2 has no global registry of grids, grids have no names and no persistent ids, and
AE2 for 1.20 no longer has a Security Terminal. A custom "link" block would make
Overseer mandatory on every client, because blocks are synced to clients.

## Decision

- **Automatic discovery** of loaded grids that have a controller. Identity: dimension +
  position of the controller block (lowest position if several). Owner: the player who
  placed the controller (AE2 records the owner of each node). Names are set in the UI.
- **Ad-hoc grids** (no controller) are discovered but hidden; admins see them under
  "hidden grids" and can adopt them (name + owner). Players can register one with
  `/ov ae2 register "<name>"` while looking at a block of the grid; that block's position
  becomes its id. Grids smaller than a threshold (default 5 nodes) are ignored.
- **Access** (default deny): controller owner and admins; the owner's FTB team; players
  the owner shares with in the UI; optionally (server setting, default off) anyone who
  placed a block in the grid.
- **Requesting access**: the UI explains the available ways and offers "request
  access" (grid name and owner visible, content not). The owner is notified in the UI
  and by in-game chat (immediately if online, otherwise on next login).

## Notes

- 2026-11-18 (M2a): "lowest position" means ordered by dimension, then x, y, z. Names are
  the generic `name` override (OverrideStore), editable by the owner. Only owner access is
  implemented so far; the other access paths follow in later M2 slices.
- 2026-11-18 (M2b): "the owner's FTB team" means an FTB Teams *party* team; team members
  may view, only the owner edits. Server teams do not count.
- 2026-11-18 (M2c): shares live in core (`resource_share`, `ShareStore`) and apply to every
  provider with `Capability.SHARING`, so other storage mods can reuse them. A share grants
  viewing only. Share targets are found among players known to the dashboard (logged in
  once), so core needs no server lookup.
- 2026-11-18 (M2d): players who cannot see a grid only get an opaque handle (HMAC of the
  resource key with a per-server secret in `meta`), its Overseer name and the owner name.
  Resource ids contain block positions and would reveal bases. Notifications are stored in
  `notification` and delivered in chat by the platform while the player is online, so
  "immediately if online, otherwise on next login" needs no login hook. A decline blocks
  new requests from that player for 24 hours.
- 2026-11-18 (M2e): registrations are a core table (`registration`), so other mods without
  ids can use them. Hidden grids get no history because their id changes with the grid.
- 2026-11-18 (M2f): inventories travel in `ProviderSnapshot.inventories` (capability
  `INVENTORY`), only on scan collections, and never in SSE pushes. Changes are stored as
  per-minute net deltas rather than every 10 s: a busy autocrafting system changes the same
  keys constantly, and the views need hours and days, not seconds. The live list comes from the
  newest scan in memory (`InventoryState`), the changes from the database. Hourly rollups for
  longer windows and the forecast come with the deficit view.
- 2026-11-18 (M2g): crafting CPUs are resources of type `cpu`, id = lowest corner of the
  multiblock (add-on CPUs without one: `<grid id>#<index>`). The running job is in attributes
  (`job`, `jobName`, `jobAmount`), so starts and ends are events and form the job history; a
  job that resumes after a restart changes nothing. Progress, total and elapsed time are
  metrics because they change every second. CPUs inherit access from their grid
  (`AccessPolicy.parent`, ADR-0008).
- 2026-11-18 (M2h): job lines (stored, active, pending per key) are live data in
  `ProviderSnapshot.jobs`: read every second, never stored, fetched only while a card is open.
  They change too fast and are too large for events or SSE pushes.
- 2026-11-18 (M2i): drives are resources like CPUs (parent = grid). Cell states are one
  attribute so that "a cell got full" is an event; bytes and types are metrics, summed per grid
  for the history chart.
- 2026-11-18 (M2j): watched items are plain metrics (`stock:<key>`) of the grid rather than a
  separate store: history, rollups, retention and SSE work unchanged. The forecast uses the net
  changes from the inventory scans (daily average) instead of a regression over raw samples; it
  is cheap and stable, and good enough for "when does this run out". Hourly inventory rollups are
  not needed for that and stay open.
- 2026-11-18 (M2k): icons travel from a client with Overseer over an optional Forge network
  channel, not an HTTP upload: the client is already authenticated as a player (operator check),
  no token is needed, and requests stay under the HTTP body limit. Exported icons are stored per
  stack key; the mod jar texture is a cheap fallback that is cached in memory, not stored.

## Consequences

- Overseer stays server-only.
- Grids exist only while loaded; unloaded grids show their last known state.
