# 0004. Threading model and snapshot hand-off

- Status: Accepted
- Date: 2026-11-17

## Context

Reading world or mod state from a non-server thread causes races and crashes. A
monitoring mod must also never become a source of lag: a slow disk or a stalled HTTP
client must not delay the tick.

## Decision

- **Only `DataProvider.collect()` reads world state**, and only on the server thread.
  It returns an immutable `ProviderSnapshot` (records, `List.copyOf`, `Map.copyOf`).
- A collector on the server tick runs providers whose `intervalTicks` are due. It
  measures each run against the provider's `budgetNanos`. A provider that overruns its
  budget repeatedly gets its interval doubled (up to a cap) and is flagged in the admin
  UI. Measured cost per provider is exposed as "collector cost".
- Snapshots are offered to a **bounded queue**. When full, the oldest snapshot is
  dropped and a counter increments. The server thread never blocks on the queue.
- A latest-state cache keeps the newest snapshot per provider for live views and SSE.
- One storage writer thread consumes the queue (see ADR-0006). HTTP runs on Netty's
  event loop with a small worker pool for DB reads.
- The UI shows data freshness ("data as of 4 s ago"), derived from the newest snapshot.

Large collections (e.g. AE2 inventories) are first done in one tick and measured.
Spreading one collection across several ticks is introduced only when measurements
show it is needed, because it creates consistency problems (the inventory changes
while it is scanned).

Since ADR-0016, world actions (changes made from the dashboard) also run on the server thread,
handed over through `ActionQueue` and applied by `ActionHandler.perform()`.

## Consequences

- No locks shared between server thread and other threads beyond the queue.
- Under overload Overseer loses data points, never ticks.
- Provider authors must copy everything they need out of mod objects; holding a
  reference to a live mod object in a snapshot is a bug.

## Alternatives considered

- **Reading on demand from HTTP threads via `server.execute(...)`**: couples request
  latency to tick timing and lets many clients add load to the tick.
- **Unbounded queue**: hides overload until memory runs out.
