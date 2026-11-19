# 0019. World actions that finish later and answer with data

- Status: Accepted
- Date: 2026-11-19

## Context

ADR-0016 runs a world action completely on the server thread within one turn and answers with
what changed. Starting an AE2 crafting job does not fit: AE2 first computes a plan on a thread of
its own (`ICraftingService#beginCraftingCalculation` returns a `Future`), often in milliseconds,
for big jobs in seconds. Players want to see that plan (bytes, used and missing items) before they
start the job, as in AE2's own confirm screen. The server thread must never block on the future.

## Decision

1. **An action may finish later.** `ActionHandler#start` returns a `CompletionStage`; the default
   completes at once with `perform`. A provider that returns an unfinished stage completes it on
   the server thread later (AE2: in its next collection, at most a second later).
2. **The caller waits for a started action**, up to `ActionQueue.CLAIMED_LIMIT` (60 s). After that
   it gets `failed`. The rule "`busy` means nothing changed" stays: only actions the server thread
   has not started yet are cancelled. Shutdown answers unfinished actions with `failed`.
3. **Results may carry data** (`ActionResult.data`, JSON object), returned to the caller and never
   written to the action log. Its shape belongs to the action type and is documented with it.
4. **Two steps for crafting**: `ae2.craft.plan` computes and keeps a plan for a few minutes and
   answers with it and its id; `ae2.craft.start` starts a kept plan, optionally on a chosen CPU.
   Both are logged like every action; only the start changes the world. Plans live in memory on
   the server thread, belong to the player who asked, and are gone after a restart.

## Consequences

- The one endpoint, permission checks and log of ADR-0016 stay; no plan endpoint of its own.
- An HTTP worker is held while a plan computes. The rate limit (60 actions per player and minute)
  and the queue size bound that.
- A plan can be outdated when it is started (items used meanwhile). AE2 checks again on submit and
  refuses missing ingredients, which the dashboard shows.

## Alternatives considered

- **Polling a plan endpoint**: needs another route with its own permission checks and lifecycle;
  the wait is short in nearly all cases.
- **Computing and starting in one step**: players could not see missing items or the size first,
  unlike in game.
- **Blocking the server thread on AE2's future**: freezes the server for big plans.
