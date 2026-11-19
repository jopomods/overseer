# 0022. Job statistics from recorded attribute events

- Status: Accepted
- Date: 2026-11-19

## Context

Players want to know what their crafting CPUs did: which items were crafted how often and in
what amounts, how long jobs took and how busy each CPU was. Overseer already records every change
of a CPU's `job`, `jobKind`, `jobName` and `jobAmount` attributes as events (ADR-0005, ADR-0011),
with wall time and uptime. Nothing else is needed from the game.

Automation CPUs can run thousands of short jobs a day. Handing all events to the browser would
be slow and capped by the event endpoint's limit of 500.

## Decision

**Aggregate in core, on request.** `GET /api/v1/jobs/stats` takes up to 64 resource keys (the UI
sends the CPUs of a network) and a window in uptime milliseconds (default: the last 24 hours) and
answers per resource the busy time and job count, and per item the jobs, the amount they asked
for, their busy time within the window and the longest finished job (at most 200 items, most jobs
first).

`JobStatsQuery` replays each resource's job attributes in insert order, starting just before
the window so the job running at its start and the companion values are known. A `job` event
ends the running job and starts the next (or idles); companion values in the same collection
belong to the new job, and a value that did not change is the previous one. A removed resource
ends its job. A job still running at the end of the window counts up to it but not as finished.

**A convention, not an AE2 type.** Core knows no CPUs: any resource that reports `job` (stack
key, empty while idle) with its companions gets statistics. Each resource must be visible to the
caller; there is no new permission.

**Uptime, not wall time.** Time the server was down does not count as busy, consistent with the
history endpoints.

## Consequences

- Statistics for every recorded day, without new tables or writes; the event index by resource
  and uptime keeps the replay cheap.
- Whether a job finished or was cancelled is unknown: AE2 reports neither, and both look like
  the CPU going idle. The UI says "jobs", not "crafted".
- The amount is what the job asked for, not what came out.

## Alternatives considered

- **A table of finished jobs written by the provider.** Exact, but a second record of what the
  events already hold, and empty for the history recorded so far.
- **Aggregating in the browser.** Too many events for automation CPUs.
