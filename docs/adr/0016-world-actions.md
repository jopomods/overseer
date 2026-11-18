# 0016. World actions: changing the game from the dashboard

- Status: Accepted
- Date: 2026-11-18

## Context

Until now Overseer only read the game. The only writes were Overseer-side settings (zones,
names of AE2 grids, shares, watchlists) stored in its own database. Players now want to manage
Flux Networks from the dashboard: rename networks and devices, change colour, priority and
limits, manage members. That changes the world, so it needs rules for who may do it, how it
reaches the server thread, and how it can be traced afterwards.

## Decision

**World action.** A request to change game state: an action type (`flux.network.edit`), a
target resource and parameters. A provider offers actions through an optional
`ActionHandler` (`DataProvider.actions()`, default none). Core knows nothing about what an
action does.

**Who may act.**

1. Never with a device token (read-only by ADR-0007), never from another origin (ADR-0007's
   same-origin check covers `POST /api/v1/actions`).
2. Server operators (checked live on every request) may run every action on everything.
3. Everyone else only where the mod's own rules allow it: `ActionHandler.canPerform` answers
   from snapshot data (e.g. Flux: owner or admin of the network).
4. Dashboard admins who are not operators get no extra rights. Seeing everything (ADR-0008) is
   not changing everything.

A target the caller cannot see answers 404, as everywhere else.

**Checked twice.** The HTTP thread checks against the last snapshot so refusals are fast. The
provider checks again on the server thread against live mod state (`Actor.operator` carries
the operator flag), because membership may have changed since the snapshot. Providers apply
changes through the mod's own code path where one exists, so the mod's validation applies too.

**Server thread.** HTTP workers put the action into `ActionQueue`. `OverseerRuntime.tick()`
drains it after collecting, at most 5 ms per tick (at least one action). The HTTP request waits
up to 10 s. An action the server thread has not claimed by then is cancelled and never runs,
so `busy` always means nothing changed; one it has claimed is waited for. Shutdown cancels
everything still waiting. The queue holds at most 64 actions; each player may send 60 per
minute.

**Outcome.** `ok`, `invalid`, `denied`, `not_found`, `unavailable` (e.g. chunk not loaded),
`busy` or `failed`, with a message. On `ok`, every changed value with its old and new value.
A provider either changes nothing or reports every change.

**Log.** Every action whose target was found is logged in `action_log` and `action_change`
(V011): time, player, operator flag, type, target, parameters, outcome, changes. Refusals are
logged too. Parameters named `password` are stored as `***`, and the API never returns
parameters. Anyone who can see a resource can read its log (actions on it or that changed it);
only dashboard admins can read the whole log. Entries are a few hundred bytes and are kept
for good; purging old ones can come with the storage settings when it matters.

**Identity.** Renames change attributes only (ADR-0005), so history and charts stay intact.

## Consequences

- One generic endpoint (`POST /api/v1/actions`) and log (`GET /api/v1/actions`) for all mods.
  The parameters of each action type are documented in the provider's module doc.
- The UI asks `GET /api/v1/actions/allowed` which actions to offer, so it never shows controls
  that would be refused.
- Actions appear in the snapshot with the next collection, not instantly.
- Every provider with actions needs GameTests that run them against the real mod, including
  refusals.

## Alternatives considered

- **One endpoint per action** (`PUT /flux/networks/{id}`): typed, but every new action would
  touch routing, permission and logging code again. Rejected; types live in the parameters.
- **Running actions directly on HTTP threads**: races with the server thread (ADR-0004).
- **Fire and forget** (answer before it ran): the UI could not show refusals or what changed.
- **Dashboard admins may act**: admins are often trusted players, not operators; changing other
  players' networks should stay with the mod's owners and the server's operators.
