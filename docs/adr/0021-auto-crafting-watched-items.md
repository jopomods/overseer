# 0021. Auto-crafting watched items up to their target

- Status: Accepted
- Date: 2026-11-19

## Context

Watched items can have a target, a minimum stock (ADR-0011). The watchlist shows the deficit and
offers "Craft the missing amount" (ADR-0019), but someone has to press it. Players keep stock with
level emitters and crafting cards in game; they asked for the same from the dashboard, without
building anything. AE2 offers everything needed through its API: the cached inventory, whether a
key is being crafted (`isRequesting`), plans (`beginCraftingCalculation`) and jobs (`submitJob`).

A job that starts on its own must still answer to the permission rules of ADR-0017: only the
grid's owner, their party and operators may make a network craft.

## Decision

**A switch per watched item.** A watch with a target can have auto-crafting on. The watch stores
who switched it on (`restock_by`, migration `V013`). Switching it on needs a target and the world
action `<provider>.restock` on the resource; AE2 offers `ae2.restock` whenever remote crafting is
on (`actions.ae2Crafting`) and the server setting `actions.ae2Restock` (default on) allows it.
`ae2.restock` is a right, not something to run: `POST /api/v1/actions` refuses it, and
`/api/v1/actions/allowed` tells the UI whether to show the switch. Removing the target switches
auto-crafting off; switching off needs only edit rights on the watchlist.

**Checked every five seconds on the server thread.** `Ae2Restocker`, called from every collection,
checks each item with auto-crafting every fifth collection:

1. The grid is looked up and the permission checked live, as for every action (`Ae2Writer.grid`),
   as the player who switched it on. Who may no longer act (left the party, lost operator rights)
   gets `denied` and nothing is crafted.
2. Stock at or above the target: `ok`. A job for the key already runs (any job, also one a player
   or an export bus started, `isRequesting`): `crafting`. No pattern: `no_pattern`.
3. Otherwise the difference is planned (`REPORT_MISSING_ITEMS`, the grid's pivot node as
   requester, as in ADR-0019) and started as soon as the plan is ready, on a CPU AE2 chooses, as
   the player (online or a stand-in with their profile). The job's link is kept: while it runs
   the item stays `crafting`.
4. Missing ingredients (`missing`), no suitable CPU (`no_cpu`) or a failure (`unavailable`) wait
   a minute before the next try, so a short network does not plan every five seconds.

**State as rows of the grid.** The state of every item with auto-crafting and since when it holds
go into the grid's live table (`row=restock`, `key`, `state`, `since`), like patterns and devices
without channel. The state is in memory only: after a restart every item is checked anew.

Jobs are not logged in the action log: they are no request of a person. AE2's own CPU history
(ADR-0011 events) records them like any other job.

## Consequences

- Networks keep stock from the dashboard alone; the watchlist shows what auto-crafting does and why
  it waits.
- A job never starts without a player who may act at that moment; turning off remote crafting or
  auto-crafting on the server stops every item at once.
- At most 50 items per grid are checked every five seconds: one inventory lookup each, a plan only
  while below target and idle.
- Two Overseer items with a shared intermediate do not know of each other; AE2's `isRequesting`
  covers the final outputs only. AE2 plans each against the current stock, as it does for two
  players.

## Alternatives considered

- **Level emitters and crafting cards placed by the mod.** Changes the world and needs free sides
  on the network; no.
- **A separate rule table instead of the watch.** Duplicates item, target and permission handling;
  the watch already has both.
- **Jobs as the machine (`IActionSource.ofMachine`).** CPUs set to "players only" would refuse
  them, and AE2 would not know who asked. The player who switched it on answers for it, as with
  ADR-0019.
- **Run the check every second.** Plans take AE2 time on its own thread; five seconds is close
  enough for stock keeping.
