# 0008. Permissions: global roles plus mod-native rules, default deny

- Status: Accepted
- Date: 2026-11-17

## Context

Flux Networks and AE2 have their own notions of ownership and membership. Players
should see exactly what the mods let them see, and Overseer should not invent a
parallel role system.

## Decision

Three layers, evaluated in order, **default deny**:

1. **Global**: server console and ops (permission level >= 2) are super-admins.
   Ops can grant **dashboard admin** to players (command or UI). Admins see everything.
2. **Provider**: each provider decides via its `AccessPolicy` whether the mod's rules
   allow a player to view a resource. Examples:
   - Flux: the player is a member of the network (any access level).
   - AE2: controller owner, owner's FTB team, explicit shares, optionally anyone who
     placed a block in the grid (ADR-0011).
   - Server: public parts (TPS, players online) for everyone logged in; details admin-only.
3. **Resource**: checks always target a concrete `ResourceRef`.

Enforcement points: every REST handler, every SSE push (re-evaluated per push against
current membership, so removed members stop receiving data immediately), history
queries and search results. Policies only read snapshot data, never live world state.

Normal users never get more than the underlying mod allows. Admin visibility beyond mod
rules is deliberate and only through layer 1.

Edit rights (M1b): `AccessPolicy.canEdit` (default `false`) lets a provider allow changes
to Overseer-side settings of a resource, e.g. Flux network owners and admins may set device
zones. Dashboard admins may always edit; device tokens never. Edit rights never exceed view
rights. Snapshots carry a per-viewer `canEdit` flag so the UI only shows edit controls
where a save would succeed.

Parts of a resource (e.g. the crafting CPUs of an ME network) name their parent through
`AccessPolicy.parent`. Core then checks the parent instead: viewing, editing and shares of
the parent apply to the part, so providers cannot get these rules out of sync.

Changes to the game itself (world actions) follow stricter rules: operators and what the mod
allows, never dashboard admins by role alone ([ADR-0016](0016-world-actions.md)).

## Consequences

- Permission tests are part of CI for every provider.
- Membership changes propagate within one collection interval.

## Alternatives considered

- **Own role/group system in the web UI**: duplicates mod state and drifts from it.
