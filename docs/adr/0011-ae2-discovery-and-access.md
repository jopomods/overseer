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

## Consequences

- Overseer stays server-only.
- Grids exist only while loaded; unloaded grids show their last known state.
